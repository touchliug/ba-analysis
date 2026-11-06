package com.ba.analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阶段识别服务（Web 版）。
 *
 * <p>把 ba-tools 的 stage 逻辑搬到服务端，供前端页面直接消费。
 * 规则与 ba-tools/Stage.java 保持一致：日线 z-score（自身 90 天）、多周期一致性、BTC 超额、证伪条件。
 *
 * <p>注意：这套标签**没有预测力**（回测显示六阶段 90% 置信区间全部跨 0），
 * 页面上只作为「状态描述」展示，不要标注成买卖信号。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StageService {

    private static final long DAY = 86_400_000L;
    private static final int LOOKBACK = 120;
    private static final int Z_WINDOW = 90;
    private static final int MIN_BARS = 40;

    private final JdbcTemplate jdbc;

    // ---------------- 对外模型 ----------------

    public record Row(String symbol, String stage, int match, boolean consistent,
                      double close, double ret, double ret7, double excess7,
                      double volRatioZ, double rangeZ, double oiChgZ, double fundingZ,
                      double drawdown, double oiFromPeak, double funding,
                      double topPos, double retail, int dataDays) {}

    public record Detail(Row row, List<String> evidence, String falsify, List<double[]> closes) {}

    // ---------------- 内部数据结构 ----------------

    record Day(long t, double close, double high, double low, double vol,
               double oi, double funding, double topPos, double retail) {}

    record Feat(double ret, double ret7, double retZ, double volRatioZ, double rangeZ,
                double oiChgZ, double fundingZ, double drawdown, double oiFromPeak,
                double funding, double topPos, double retail, double excess7) {}

    record Verdict(String stage, int match, boolean consistent, List<String> evidence) {}

    // ---------------- 对外接口 ----------------

    /** 全市场扫描，按匹配度降序。 */
    public List<Row> scan() {
        Map<String, List<Day>> series = load();
        double[] btc = btcReturns(series);
        List<Row> rows = new ArrayList<>();
        for (var e : series.entrySet()) {
            Row r = toRow(e.getKey(), e.getValue(), btc);
            if (r != null) rows.add(r);
        }
        rows.sort(Comparator.comparingInt(Row::match).reversed());
        log.info("stage scan: {} symbols", rows.size());
        return rows;
    }

    /** 单币详情（含证据、证伪条件、近 90 天收盘价序列）。 */
    public Detail detail(String symbol) {
        Map<String, List<Day>> series = load();
        List<Day> d = series.get(symbol);
        if (d == null || d.size() < MIN_BARS) return null;
        double[] btc = btcReturns(series);

        Row row = toRow(symbol, d, btc);
        if (row == null) return null;

        Feat f = feature(d, d.size() - 1, btc);
        Verdict v = classify(f);

        List<double[]> closes = new ArrayList<>();
        int from = Math.max(0, d.size() - 90);
        for (int i = from; i < d.size(); i++) closes.add(new double[]{d.get(i).t(), d.get(i).close()});
        return new Detail(row, v.evidence(), falsify(v.stage()), closes);
    }

    // ---------------- 计算 ----------------

    private Row toRow(String symbol, List<Day> d, double[] btc) {
        if (d.size() < MIN_BARS) return null;
        Feat f = feature(d, d.size() - 1, btc);
        if (f == null) return null;
        Verdict v = classify(f);
        return new Row(symbol, v.stage(), v.match(), v.consistent(), d.get(d.size() - 1).close(),
                f.ret(), f.ret7(), f.excess7(), f.volRatioZ(), f.rangeZ(), f.oiChgZ(), f.fundingZ(),
                f.drawdown(), f.oiFromPeak(), f.funding(), f.topPos(), f.retail(), d.size());
    }

    Feat feature(List<Day> d, int i, double[] btc) {
        if (i < MIN_BARS || i >= d.size()) return null;
        Day today = d.get(i);
        double prev = d.get(i - 1).close();
        double ret = prev > 0 ? today.close() / prev - 1 : 0;
        double ret7 = i >= 7 && d.get(i - 7).close() > 0 ? today.close() / d.get(i - 7).close() - 1 : 0;

        double hi30 = 0, oiPeak = 0;
        for (int j = Math.max(0, i - 30); j <= i; j++) {
            hi30 = Math.max(hi30, d.get(j).high());
            if (!Double.isNaN(d.get(j).oi())) oiPeak = Math.max(oiPeak, d.get(j).oi());
        }
        double drawdown = hi30 > 0 ? today.close() / hi30 - 1 : 0;
        double oiFromPeak = (oiPeak > 0 && !Double.isNaN(today.oi())) ? today.oi() / oiPeak - 1 : Double.NaN;

        return new Feat(ret, ret7, zRet(d, i), zRatio(d, i),
                z(d, i, x -> x.low() > 0 ? (x.high() - x.low()) / x.low() : 0),
                zOi(d, i), z(d, i, x -> x.funding()),
                drawdown, oiFromPeak, today.funding(), today.topPos(), today.retail(), ret7 - btc[1]);
    }

    private interface Fn { double get(Day d); }

    /**
     * 日收益的 z-score：今天的涨跌幅相对过去 90 天日收益分布的偏离。
     *
     * <p>注意不能用 {@code z(d, i, x -> x.close())} —— 那是「价格水平」的 z，
     * 对一枚从 0.59 跌到 0.23 再反弹的币，价格 z 只有 0.2，单日 +24% 的信息会被完全丢掉，
     * 而分类规则（日涨幅 z&gt;1.5 判拉升）要的恰恰是收益 z。
     */
    private static double zRet(List<Day> d, int i) {
        int from = Math.max(1, i - Z_WINDOW);
        double sum = 0, sq = 0;
        int c = 0;
        for (int j = from; j < i; j++) {
            double prev = d.get(j - 1).close();
            if (prev <= 0 || d.get(j).close() <= 0) continue;
            double r = d.get(j).close() / prev - 1;
            sum += r; sq += r * r; c++;
        }
        if (c < 20) return 0;
        double mean = sum / c;
        double var = sq / c - mean * mean;
        if (var <= 0) return 0;
        double prev = d.get(i - 1).close();
        if (prev <= 0) return 0;
        return (d.get(i).close() / prev - 1 - mean) / Math.sqrt(var);
    }

    private static double z(List<Day> d, int i, Fn fn) {
        int from = Math.max(0, i - Z_WINDOW);
        double sum = 0, sq = 0;
        int c = 0;
        for (int j = from; j < i; j++) {
            double v = fn.get(d.get(j));
            if (Double.isNaN(v)) continue;
            sum += v; sq += v * v; c++;
        }
        if (c < 20) return 0;
        double mean = sum / c;
        double var = sq / c - mean * mean;
        if (var <= 0) return 0;
        double cur = fn.get(d.get(i));
        return Double.isNaN(cur) ? 0 : (cur - mean) / Math.sqrt(var);
    }

    private static double zRatio(List<Day> d, int i) {
        double cur = ratioAt(d, i);
        int from = Math.max(8, i - Z_WINDOW);
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

    private static double ratioAt(List<Day> d, int i) {
        if (i < 8) return Double.NaN;
        double avg = 0;
        for (int j = i - 7; j < i; j++) avg += d.get(j).vol();
        avg /= 7;
        return avg > 0 ? d.get(i).vol() / avg : Double.NaN;
    }

    private static double zOi(List<Day> d, int i) {
        double cur = oiChgAt(d, i);
        int from = Math.max(1, i - Z_WINDOW);
        double sum = 0, sq = 0;
        int c = 0;
        for (int j = from; j < i; j++) {
            double v = oiChgAt(d, j);
            if (Double.isNaN(v)) continue;
            sum += v; sq += v * v; c++;
        }
        if (c < 20) return 0;
        double mean = sum / c;
        double var = sq / c - mean * mean;
        if (var <= 0) return 0;
        return Double.isNaN(cur) ? 0 : (cur - mean) / Math.sqrt(var);
    }

    private static double oiChgAt(List<Day> d, int i) {
        if (i < 1) return Double.NaN;
        double a = d.get(i - 1).oi(), b = d.get(i).oi();
        if (Double.isNaN(a) || Double.isNaN(b) || a <= 0) return Double.NaN;
        return b / a - 1;
    }

    Verdict classify(Feat f) {
        List<String> ev = new ArrayList<>();
        int dump = 0, markup = 0, dist = 0, test = 0, accum = 0;

        if (f.retZ() < -2.0) { dump += 35; ev.add(String.format("日跌幅 z=%.1f", f.retZ())); }
        if (f.oiChgZ() < -1.5) { dump += 30; ev.add(String.format("OI 降幅 z=%.1f", f.oiChgZ())); }
        if (f.drawdown() < -0.25) dump += 25;

        if (f.retZ() > 1.5) { markup += 30; ev.add(String.format("日涨幅 z=%.1f", f.retZ())); }
        if (f.oiChgZ() > 1.0) { markup += 30; ev.add(String.format("OI 增幅 z=%.1f", f.oiChgZ())); }
        if (f.volRatioZ() > 1.0) markup += 20;
        if (f.drawdown() < -0.40) markup -= 25;

        if (f.drawdown() > -0.08) dist += 30;
        if (!Double.isNaN(f.oiFromPeak()) && f.oiFromPeak() < -0.05) {
            dist += 25; ev.add(String.format("OI 距峰 %.1f%%", f.oiFromPeak() * 100));
        }
        if (f.volRatioZ() < -0.8) { dist += 25; ev.add(String.format("缩量 z=%.1f", f.volRatioZ())); }
        if (f.funding() > 0.0002) dist += 20;

        if (f.rangeZ() > 2.0) { test += 30; ev.add(String.format("振幅 z=%.1f", f.rangeZ())); }
        if (Math.abs(f.retZ()) < 0.8) test += 25;
        if (f.volRatioZ() > 1.5) test += 25;
        if (Math.abs(f.oiChgZ()) > 1.5) test += 20;

        if (Math.abs(f.retZ()) < 0.6) accum += 25;
        if (f.volRatioZ() < -1.0) { accum += 25; ev.add(String.format("地量 z=%.1f", f.volRatioZ())); }
        if (!Double.isNaN(f.oiFromPeak()) && f.oiFromPeak() > -0.10) accum += 20;
        if (Math.abs(f.funding()) < 0.0002) accum += 15;
        if (f.drawdown() < -0.20) accum += 15;

        String[] names = {"砸盘", "拉升", "派发", "试盘", "吸筹"};
        int[] scores = {dump, markup, dist, test, accum};
        int best = 0;
        String stage = "冷却";
        for (int i = 0; i < names.length; i++) {
            if (scores[i] > best) { best = scores[i]; stage = names[i]; }
        }
        if (best < 50) { stage = "冷却"; best = Math.max(best, 30); }

        boolean consistent = true;
        if ("拉升".equals(stage) && f.ret7() < -0.05) consistent = false;
        if ("砸盘".equals(stage) && f.ret7() > 0.08) consistent = false;
        if ("吸筹".equals(stage) && f.ret7() < -0.15) consistent = false;

        return new Verdict(stage, Math.min(100, best), consistent, ev);
    }

    private static String falsify(String stage) {
        return switch (stage) {
            case "拉升" -> "若放量站不上近 30 日高点，或 OI 转向下降，则拉升判断失效";
            case "派发" -> "若放量突破近 30 日高点且 OI 同步上升，则派发判断失效";
            case "砸盘" -> "若 3 日内收复前一日跌幅一半且 OI 回升，则砸盘判断失效";
            case "吸筹" -> "若放量跌破近 30 日低点，则吸筹判断失效";
            case "试盘" -> "若振幅收敛且 OI 回落至常态，则试盘结束";
            default -> "无明确结构，不需要证伪";
        };
    }

    // ---------------- 数据加载 ----------------

    private record CachedSeries(long at, Map<String, List<Day>> data) {}

    /** 一次加载全市场 × 120 天要几十秒，页面每切一次都要读，故做 60 秒缓存。 */
    private volatile CachedSeries seriesCache;

    private static final long SERIES_TTL_MS = 60_000L;

    /** 冷加载单飞: 过期瞬间只允许一个线程查库, 其余线程等待并复用结果。 */
    private final Object seriesLock = new Object();

    /** 启动后异步预热：冷加载约 20s，预热后第一个用户请求就是热的。 */
    @jakarta.annotation.PostConstruct
    void warmUp() {
        Thread t = new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                load();
                log.info("stage cache warmed in {} ms", System.currentTimeMillis() - t0);
            } catch (Exception e) {
                log.warn("stage cache warm-up failed (will retry on first request)", e);
            }
        }, "stage-warmup");
        t.setDaemon(true);
        t.start();
    }

    Map<String, List<Day>> load() {
        CachedSeries c = seriesCache;
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < SERIES_TTL_MS) return c.data();

        synchronized (seriesLock) {
            // 双重检查: 等锁期间可能已有其他线程完成加载
            c = seriesCache;
            now = System.currentTimeMillis();
            if (c != null && now - c.at() < SERIES_TTL_MS) return c.data();

            Map<String, List<Day>> loaded = loadFromDb();
            // 用加载完成后的时间戳: loadFromDb 要几十秒, 用调用前的时间会让缓存一写入就过期
            seriesCache = new CachedSeries(System.currentTimeMillis(), loaded);
            log.info("stage series cache refreshed: {} symbols", loaded.size());
            return loaded;
        }
    }

    private Map<String, List<Day>> loadFromDb() {
        long since = System.currentTimeMillis() - (long) LOOKBACK * DAY;

        Set<String> delisted = new HashSet<>(jdbc.queryForList(
                "SELECT symbol FROM symbols WHERE status <> 'TRADING'", String.class));

        Map<String, List<double[]>> k = new HashMap<>();
        jdbc.query("SELECT symbol, open_time, CAST(`close` AS DECIMAL(30,10)) c, CAST(high AS DECIMAL(30,10)) h, "
                 + "CAST(low AS DECIMAL(30,10)) l, CAST(quote_asset_volume AS DECIMAL(30,2)) v "
                 + "FROM klines WHERE `interval`='1d' AND open_time >= ? ORDER BY symbol, open_time",
                rs -> {
                    String sym = rs.getString("symbol");
                    if (delisted.contains(sym)) return;
                    k.computeIfAbsent(sym, x -> new ArrayList<>())
                            .add(new double[]{rs.getLong("open_time"), rs.getDouble("c"), rs.getDouble("h"),
                                    rs.getDouble("l"), rs.getDouble("v")});
                }, since);

        Map<String, Map<Long, Double>> oi = new HashMap<>();
        jdbc.query("SELECT symbol, `timestamp` t, CAST(sum_open_interest AS DECIMAL(30,2)) v "
                 + "FROM open_interest WHERE period='1d' AND `timestamp` >= ?",
                rs -> {
                    Object v = rs.getObject("v");
                    if (v == null) return;
                    oi.computeIfAbsent(rs.getString("symbol"), x -> new HashMap<>())
                            .put(rs.getLong("t"), ((Number) v).doubleValue());
                }, since);

        // 资金费率: 在 SQL 里按天聚合。库里是 8h 一条, 120 天 × 522 币约 18 万行,
        // 全量传回 Java 再聚合明显慢于让 MySQL 做 GROUP BY。
        Map<String, Map<Long, double[]>> fr = new HashMap<>();
        jdbc.query("SELECT symbol, (funding_time DIV ?) * ? AS day, "
                 + "AVG(CAST(funding_rate AS DECIMAL(20,10))) r, COUNT(*) n "
                 + "FROM funding_rate WHERE funding_time >= ? GROUP BY symbol, day",
                rs -> {
                    long dayKey = rs.getLong("day");
                    double[] acc = fr.computeIfAbsent(rs.getString("symbol"), x -> new HashMap<>())
                            .computeIfAbsent(dayKey, x -> new double[2]);
                    acc[0] = rs.getDouble("r") * rs.getInt("n");
                    acc[1] = rs.getInt("n");
                }, DAY, DAY, since);

        // 多空比只用到"当前值"(分类与筛选都不对它做 z-score), 因此只取最近 3 天, 不跟着 120 天窗口走。
        // 这一步是全流程最贵的查询: 全量拉 120 天 = 522 币 × 120 天 × 24h × 2 类 ≈ 300 万行。
        Map<String, Map<Long, double[]>> lsr = new HashMap<>();
        jdbc.query("SELECT symbol, (`timestamp` DIV ?) * ? AS day, ratio_type, "
                 + "AVG(CAST(long_short_ratio AS DECIMAL(20,6))) r "
                 + "FROM long_short_ratio WHERE period='1h' AND `timestamp` >= ? "
                 + "AND ratio_type IN ('TOP_POSITION','GLOBAL_ACCOUNT') "
                 + "GROUP BY symbol, day, ratio_type",
                rs -> {
                    long dayKey = rs.getLong("day");
                    double[] acc = lsr.computeIfAbsent(rs.getString("symbol"), x -> new HashMap<>())
                            .computeIfAbsent(dayKey, x -> new double[]{Double.NaN, Double.NaN});
                    if ("TOP_POSITION".equals(rs.getString("ratio_type"))) acc[0] = rs.getDouble("r");
                    else acc[1] = rs.getDouble("r");
                }, DAY, DAY, System.currentTimeMillis() - 3 * DAY);

        Map<String, List<Day>> out = new LinkedHashMap<>();
        for (var e : k.entrySet()) {
            String sym = e.getKey();
            List<double[]> bars = e.getValue();
            if (bars.size() < MIN_BARS) continue;
            List<Day> days = new ArrayList<>(bars.size());
            for (double[] b : bars) {
                long dayKey = (long) b[0] / DAY * DAY;
                Double oiv = oi.getOrDefault(sym, Map.of()).get(dayKey);
                double[] facc = fr.getOrDefault(sym, Map.of()).getOrDefault(dayKey, null);
                double[] lacc = lsr.getOrDefault(sym, Map.of()).getOrDefault(dayKey, null);
                days.add(new Day((long) b[0], b[1], b[2], b[3], b[4],
                        oiv == null ? Double.NaN : oiv,
                        facc == null ? Double.NaN : facc[0] / facc[1],
                        lacc == null ? Double.NaN : lacc[0],
                        lacc == null ? Double.NaN : lacc[1]));
            }
            out.put(sym, days);
        }
        return out;
    }

    private static double[] btcReturns(Map<String, List<Day>> series) {
        List<Day> b = series.get("BTCUSDT");
        if (b == null || b.size() < 8) return new double[]{0, 0};
        int i = b.size() - 1;
        double r7 = b.get(i - 7).close() > 0 ? b.get(i).close() / b.get(i - 7).close() - 1 : 0;
        return new double[]{0, r7};
    }
}
