package com.ba.analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 选币筛选器。
 *
 * <p>数据只加载一次（复用 {@link StageService#load()}），六种筛选器共用同一份指标，
 * 每种只做「过滤 + 排序」，因此加一个新筛选器的成本很低。
 *
 * <p>所有指标都基于日线（120 天），并且尽量用**相对自身历史的分位/z-score**，
 * 避免"量比 1.5"这种对大盘股和小盘股意义不同的绝对值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenerService {

    private static final long DAY = 86_400_000L;

    private final StageService stageService;

    /** 筛选器定义：类型 → (标题, 说明)。 */
    public record Def(String type, String title, String desc) {}

    public static final List<Def> DEFS = List.of(
            new Def("anomaly", "放量异动", "量比 z-score > 0.8 且单日涨跌幅 > 1.5%，先看发生了什么"),
            new Def("oi", "持仓异动", "OI 单日变化超过 8%，配合价格方向判断是新多/新空还是平仓"),
            new Def("crowded", "费率极值", "资金费率年化 > 50%，多头拥挤、持仓成本高"),
            new Def("divergence", "多空背离", "大户持仓比与散户账户比差值 > 0.8，典型的「人少钱多 vs 人多钱少」"),
            new Def("squeeze", "缩量横盘", "量比 < 0.6 + 20 日振幅 < 15% + 7 日涨跌幅绝对值 < 5%，可能是吸筹阶段"),
            new Def("breakout", "逼近新高", "距 20 日高点 8% 以内 + 量比 > 1.0 + OI 一周变化 > -5%")
    );

    /** 单币指标。 */
    public record Row(String symbol, double close,
                      double ret1, double ret7, double ret20,
                      double volRatio, double volRatioZ,
                      double range20, double distHigh20,
                      double oiChg1, double oiChg7, double oiFromPeak,
                      double funding, double fundingApr,
                      double topPos, double retail, double divergence,
                      int dataDays, String reason) {}

    private record Cached(long at, List<Row> rows) {}

    /** 缓存 60 秒：一次加载全市场 × 120 天要几十秒，六个筛选器必须共用同一份结果。 */
    private volatile Cached cache;

    private static final long CACHE_TTL_MS = 60_000L;

    /** 冷加载单飞: 过期瞬间只允许一个线程构建指标, 其余线程等待并复用结果。 */
    private final Object cacheLock = new Object();

    /** 全市场指标（带 TTL 缓存）。 */
    private List<Row> cache() {
        Cached c = cache;
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < CACHE_TTL_MS) return c.rows();

        synchronized (cacheLock) {
            // 双重检查: 等锁期间可能已有其他线程完成构建
            c = cache;
            now = System.currentTimeMillis();
            if (c != null && now - c.at() < CACHE_TTL_MS) return c.rows();

            Map<String, List<StageService.Day>> series = stageService.load();
            List<Row> rows = new ArrayList<>();
            for (var e : series.entrySet()) {
                Row r = metrics(e.getKey(), e.getValue());
                if (r != null) rows.add(r);
            }
            cache = new Cached(System.currentTimeMillis(), rows);
            log.info("screener cache refreshed: {} symbols", rows.size());
            return rows;
        }
    }

    public List<Row> screen(String type, int limit) {
        List<Row> all = cache();
        List<Row> picked = switch (type == null ? "" : type) {
            case "anomaly" -> all.stream()
                    .filter(r -> r.volRatioZ() > 0.8 && Math.abs(r.ret1()) > 0.015)
                    .sorted(Comparator.comparingDouble((Row r) -> Math.abs(r.ret1())).reversed())
                    .limit(limit)
                    .toList();
            case "oi" -> all.stream()
                    .filter(r -> Math.abs(r.oiChg1()) > 0.08)
                    .sorted(Comparator.comparingDouble((Row r) -> Math.abs(r.oiChg1())).reversed())
                    .limit(limit)
                    .toList();
            case "crowded" -> all.stream()
                    .filter(r -> r.fundingApr() > 0.5)
                    .sorted(Comparator.comparingDouble(Row::fundingApr).reversed())
                    .limit(limit)
                    .toList();
            case "divergence" -> all.stream()
                    .filter(r -> r.divergence() > 0.8)
                    .sorted(Comparator.comparingDouble(Row::divergence).reversed())
                    .limit(limit)
                    .toList();
            case "squeeze" -> all.stream()
                    .filter(r -> r.volRatio() < 0.6 && r.range20() < 15 && Math.abs(r.ret7()) < 0.05)
                    .sorted(Comparator.comparingDouble(Row::volRatio))
                    .limit(limit)
                    .toList();
            case "breakout" -> all.stream()
                    .filter(r -> r.distHigh20() > -0.08 && r.volRatio() > 1.0 && r.oiChg7() > -0.05)
                    .sorted(Comparator.comparingDouble(Row::distHigh20).reversed())
                    .limit(limit)
                    .toList();
            default -> List.of();
        };
        log.info("screen {} -> {} rows", type, picked.size());
        return picked;
    }

    // ---------------- 指标计算 ----------------

    private Row metrics(String symbol, List<StageService.Day> d) {
        int n = d.size();
        if (n < 40) return null;
        int i = n - 1;
        StageService.Day today = d.get(i);
        double close = today.close();
        if (close <= 0) return null;

        double ret1 = pct(today.close(), d.get(i - 1).close());
        double ret7 = i >= 7 ? pct(today.close(), d.get(i - 7).close()) : 0;
        double ret20 = i >= 20 ? pct(today.close(), d.get(i - 20).close()) : 0;

        double volAvg = 0;
        for (int j = i - 7; j < i; j++) volAvg += d.get(j).vol();
        volAvg /= 7;
        double volRatio = volAvg > 0 ? today.vol() / volAvg : 1;

        double hi20 = 0, lo20 = Double.MAX_VALUE, oiPeak30 = 0;
        for (int j = Math.max(0, i - 19); j <= i; j++) {
            hi20 = Math.max(hi20, d.get(j).high());
            lo20 = Math.min(lo20, d.get(j).low());
        }
        for (int j = Math.max(0, i - 29); j <= i; j++) {
            if (!Double.isNaN(d.get(j).oi())) oiPeak30 = Math.max(oiPeak30, d.get(j).oi());
        }
        double range20 = lo20 > 0 && lo20 != Double.MAX_VALUE ? (hi20 - lo20) / lo20 * 100 : 0;
        double distHigh20 = hi20 > 0 ? today.close() / hi20 - 1 : 0;

        double oiChg1 = (Double.isNaN(today.oi()) || Double.isNaN(d.get(i - 1).oi()) || d.get(i - 1).oi() <= 0)
                ? 0 : today.oi() / d.get(i - 1).oi() - 1;
        double oiChg7 = (i < 7 || Double.isNaN(today.oi()) || Double.isNaN(d.get(i - 7).oi()) || d.get(i - 7).oi() <= 0)
                ? 0 : today.oi() / d.get(i - 7).oi() - 1;
        double oiFromPeak = (oiPeak30 > 0 && !Double.isNaN(today.oi())) ? today.oi() / oiPeak30 - 1 : 0;

        double funding = Double.isNaN(today.funding()) ? 0 : today.funding();
        double fundingApr = funding * 3 * 365;   // 每 8 小时一次 → 年化近似
        double topPos = Double.isNaN(today.topPos()) ? 0 : today.topPos();
        double retail = Double.isNaN(today.retail()) ? 0 : today.retail();

        return new Row(symbol, close, ret1, ret7, ret20, volRatio, volRatioZ(d, i),
                range20, distHigh20, oiChg1, oiChg7, oiFromPeak,
                funding, fundingApr, topPos, retail, topPos - retail, n, reason(ret1, volRatio, oiChg1, funding));
    }

    private static double pct(double a, double b) {
        return b > 0 ? a / b - 1 : 0;
    }

    /** 量比相对自身 90 天的 z-score。 */
    private static double volRatioZ(List<StageService.Day> d, int i) {
        double cur = ratioAt(d, i);
        int from = Math.max(8, i - 90);
        double sum = 0, sq = 0;
        int c = 0;
        for (int j = from; j < i; j++) {
            double v = ratioAt(d, j);
            if (Double.isNaN(v)) continue;
            sum += v; sq += v * v; c++;
        }
        if (c < 20) return 0;
        double mean = sum / c;
        double var = sq / c - mean * mean;
        if (var <= 0) return 0;
        return (cur - mean) / Math.sqrt(var);
    }

    private static double ratioAt(List<StageService.Day> d, int i) {
        if (i < 8) return Double.NaN;
        double avg = 0;
        for (int j = i - 7; j < i; j++) avg += d.get(j).vol();
        avg /= 7;
        return avg > 0 ? d.get(i).vol() / avg : Double.NaN;
    }

    /** 一句话理由（给页面直接展示）。 */
    private static String reason(double ret1, double volRatio, double oiChg1, double funding) {
        StringBuilder sb = new StringBuilder();
        if (ret1 > 0.05) sb.append("日涨 ").append(String.format("%.1f%%", ret1 * 100));
        else if (ret1 < -0.05) sb.append("日跌 ").append(String.format("%.1f%%", ret1 * 100));
        if (volRatio > 1.5) sb.append(sb.length() > 0 ? " · " : "").append("放量 ").append(String.format("%.1fx", volRatio));
        else if (volRatio < 0.6) sb.append(sb.length() > 0 ? " · " : "").append("缩量 ").append(String.format("%.1fx", volRatio));
        if (Math.abs(oiChg1) > 0.05) sb.append(sb.length() > 0 ? " · " : "")
                .append("OI ").append(String.format("%+.1f%%", oiChg1 * 100));
        if (Math.abs(funding) > 0.0002) sb.append(sb.length() > 0 ? " · " : "")
                .append("费率 ").append(String.format("%.4f%%", funding * 100));
        return sb.length() == 0 ? "—" : sb.toString();
    }
}
