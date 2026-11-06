package com.ba.analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 盘中异动预警。
 *
 * <p>回溯 N 小时（默认 6），把窗口内每一根小时线都过一遍规则，因此既能当实时预警，
 * 也能回答"刚才发生了什么"。
 *
 * <p>四类信号：
 * <ul>
 *   <li><b>PUMP</b>：1h 涨幅 ≥ 8% 且 量比 ≥ 5</li>
 *   <li><b>DUMP</b>：1h 跌幅 ≥ 8% 且 量比 ≥ 5</li>
 *   <li><b>VOL</b>：量比 ≥ 10（不看方向）</li>
 *   <li><b>OI</b>：1h OI 变化 ≥ 15%</li>
 * </ul>
 *
 * <p>成交额门槛：24h ≥ 300 万 U <b>或</b> 当根 1h ≥ 100 万 U。
 * 只看 24h 会把"刚启动的第一根大阳线"滤掉——那时 24h 还很小，而这正是最该预警的时刻。
 *
 * <p>性能：查询按 symbol 走主键前缀（不能只按 interval+open_time，那样是 700 万行全表扫）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private static final long HOUR = 3_600_000L;

    private static final double MIN_QUOTE_24H = 3_000_000;   // 24h 成交额下限 300 万 U
    private static final double MIN_QUOTE_1H = 1_000_000;    // 或单根小时线 100 万 U

    private final JdbcTemplate jdbc;

    public record Alert(String symbol, String type, long time, double price, double chg1h,
                        double volRatio, double quote24h, double oiChg1h, double funding,
                        String note) {}

    public List<Alert> scan(int backHours) {
        int hours = Math.max(1, Math.min(24, backHours));
        long now = System.currentTimeMillis();
        // 每根被扫描的 bar 都要往前看 24 根算基准, 所以窗口 = 回溯时长 + 25 小时。
        // 之前固定 26 小时, 结果只有最后一根能凑够前 24 根, 历史 bar 全被跳过。
        long since = now - (hours + 25) * HOUR;
        long from = now - hours * HOUR;

        List<String> symbols = jdbc.queryForList(
                "SELECT symbol FROM symbols WHERE status='TRADING'", String.class);
        if (symbols.isEmpty()) return List.of();
        String in = String.join(",", Collections.nCopies(symbols.size(), "?"));

        // 1) 1h K 线：symbol IN (...) 走主键前缀，避免全表扫
        Object[] kArgs = new Object[symbols.size() + 1];
        kArgs[0] = since;
        for (int i = 0; i < symbols.size(); i++) kArgs[i + 1] = symbols.get(i);
        Map<String, List<double[]>> bars = new HashMap<>();   // t, close, qvol
        jdbc.query("SELECT symbol, open_time, CAST(`close` AS DECIMAL(30,10)) c, "
                 + "CAST(quote_asset_volume AS DECIMAL(30,2)) v "
                 + "FROM klines WHERE `interval`='1h' AND open_time >= ? AND symbol IN (" + in + ") "
                 + "ORDER BY symbol, open_time",
                rs -> {
                    bars.computeIfAbsent(rs.getString("symbol"), x -> new ArrayList<>())
                            .add(new double[]{rs.getLong("open_time"), rs.getDouble("c"), rs.getDouble("v")});
                }, kArgs);

        // 2) 1h OI
        Map<String, Map<Long, Double>> ois = new HashMap<>();
        jdbc.query("SELECT symbol, `timestamp` t, CAST(sum_open_interest AS DECIMAL(30,2)) v "
                 + "FROM open_interest WHERE period='1h' AND `timestamp` >= ? AND symbol IN (" + in + ")",
                rs -> {
                    Object v = rs.getObject("v");
                    if (v == null) return;
                    ois.computeIfAbsent(rs.getString("symbol"), x -> new HashMap<>())
                            .put(rs.getLong("t"), ((Number) v).doubleValue());
                }, kArgs);

        // 3) 最近资金费率（每币取最新一条）
        Map<String, Double> funding = new HashMap<>();
        jdbc.query("SELECT symbol, CAST(funding_rate AS DECIMAL(20,10)) r FROM funding_rate "
                 + "WHERE funding_time >= ? AND symbol IN (" + in + ") ORDER BY funding_time",
                rs -> {
                    funding.put(rs.getString("symbol"), rs.getDouble("r"));
                }, kArgs);

        List<Alert> out = new ArrayList<>();
        for (var e : bars.entrySet()) {
            String sym = e.getKey();
            List<double[]> b = e.getValue();
            if (b.size() < 26) continue;

            Map<Long, Double> oi = ois.getOrDefault(sym, Map.of());
            double f = funding.getOrDefault(sym, 0.0);

            for (int j = 25; j < b.size(); j++) {
                long t = (long) b.get(j)[0];
                if (t < from) continue;                       // 只看回溯窗口
                if (t + HOUR > now) continue;                 // 跳过还没走完的那根

                double close = b.get(j)[1], prev = b.get(j - 1)[1];
                if (prev <= 0) continue;
                double chg = close / prev - 1;
                double vol1h = b.get(j)[2];

                double quote24h = 0;
                for (int q = j - 23; q <= j; q++) quote24h += b.get(q)[2];
                double avgHourly = quote24h / 24;
                double volRatio = avgHourly > 0 ? vol1h / avgHourly : 0;

                if (quote24h < MIN_QUOTE_24H && vol1h < MIN_QUOTE_1H) continue;

                double oiChg = 0;
                Double oiNow = oi.get(t), oiPrev = oi.get(t - HOUR);
                if (oiNow != null && oiPrev != null && oiPrev > 0) oiChg = oiNow / oiPrev - 1;

                String note = String.format("成交 %.0f万 · 量比 %.1fx · OI %+.1f%% · 费率 %.4f%%",
                        quote24h / 10000, volRatio, oiChg * 100, f * 100);

                if (Math.abs(chg) >= 0.08 && volRatio >= 5) {
                    out.add(new Alert(sym, chg > 0 ? "PUMP" : "DUMP", t, close, chg, volRatio, quote24h, oiChg, f, note));
                } else if (volRatio >= 10) {
                    out.add(new Alert(sym, "VOL", t, close, chg, volRatio, quote24h, oiChg, f, note));
                }
                if (Math.abs(oiChg) >= 0.15) {
                    out.add(new Alert(sym, "OI", t, close, chg, volRatio, quote24h, oiChg, f, note));
                }
            }
        }

        out.sort(Comparator.comparingLong(Alert::time).reversed()
                .thenComparing(Comparator.comparingDouble(Alert::volRatio).reversed()));
        log.info("alert scan(back {}h): {} signals", hours, out.size());
        return out.size() > 300 ? out.subList(0, 300) : out;
    }
}
