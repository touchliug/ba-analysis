package com.ba.analyzer.service;

import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 小时连涨 + OI 净增扫描（独立页面，不走分析器框架）。
 *
 * <p>口径：最近 N 根<strong>已收盘</strong> 1h K 线全部 {@code close > open}，且同一窗口内
 * {@code sum_open_interest}（币本位数量）净增。允许中间回落，只看区间两端。
 *
 * <p>只读 MySQL（经 {@link DataFetchService}），结果按 periods 分槽缓存在内存，不落库。
 */
@Slf4j
@Service
public class HourlyRiseOiService {

    public static final int MIN_PERIODS = 2;
    public static final int MAX_PERIODS = 24;

    private static final long HOUR_MS = 3_600_000L;
    /** OI 端点容差：OI 是整点快照，窗口端点是 :59:59.999。 */
    private static final long OI_TOLERANCE_MS = 30 * 60_000L;
    /** 端口点退化时，距窗口结束最远允许 2 小时。 */
    private static final long OI_FALLBACK_MAX_MS = 2 * HOUR_MS;
    /** 退化端点至少要覆盖 2 小时，否则不算「窗口净增」。 */
    private static final long OI_MIN_SPAN_MS = 2 * HOUR_MS;
    /** 最新已收盘 K 线超过 2 小时视为同步滞后。 */
    private static final long KLINE_STALE_MS = 2 * HOUR_MS;
    /** 多取一些根，用于 24h 成交额与停牌缺口。 */
    private static final int EXTRA_BARS = 20;

    /** 命中项。百分比字段是小数（0.0412 = 4.12%），与 AlertService 一致。 */
    public record Match(String symbol, double price, double priceChangePercent,
                        double oiChangePercent, int periods, long windowStart,
                        long windowEnd, long oiStartTime, long oiEndTime,
                        double quoteVolume24h) {
    }

    /** 一次扫描的完整结果。 */
    public record ScanResult(long scannedAt, int totalSymbols, int matchedCount,
                             int periods, int skippedNoOi, List<Match> matches) {
    }

    private final DataFetchService dataFetchService;
    private final SymbolService symbolService;
    private final Map<Integer, ScanResult> slots = new ConcurrentHashMap<>();

    public HourlyRiseOiService(DataFetchService dataFetchService, SymbolService symbolService) {
        this.dataFetchService = dataFetchService;
        this.symbolService = symbolService;
    }

    public static int clampPeriods(int periods) {
        return Math.min(Math.max(periods, MIN_PERIODS), MAX_PERIODS);
    }

    /** 立即扫描并写入缓存槽（调度器走这里，不做锁与缓存判断）。 */
    public ScanResult scan(int periods) {
        int n = clampPeriods(periods);
        long startedAt = System.currentTimeMillis();
        List<String> symbols = symbolService.getSymbolNames();
        if (symbols == null || symbols.isEmpty()) {
            ScanResult empty = new ScanResult(startedAt, 0, 0, n, 0, List.of());
            slots.put(n, empty);
            log.warn("hourly-rise-oi scan: no symbols (sync service may not have run yet)");
            return empty;
        }

        int limit = Math.max(n + EXTRA_BARS, 24);
        Map<String, List<KlineData>> klineMap = dataFetchService.fetchKlines(symbols, "1h", limit);
        Map<String, List<OpenInterestData>> oiMap =
                dataFetchService.fetchOpenInterestHistoryBatch(symbols, "1h", limit);
        if (klineMap == null) klineMap = Map.of();
        if (oiMap == null) oiMap = Map.of();

        List<Match> matches = new ArrayList<>();
        int skippedNoOi = 0;

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> all = entry.getValue();
            if (all == null || all.isEmpty()) continue;

            List<KlineData> closed = new ArrayList<>();
            for (KlineData k : all) {
                if (k.getCloseTime() < startedAt) closed.add(k);
            }
            if (closed.isEmpty()) continue;
            if (startedAt - closed.get(closed.size() - 1).getCloseTime() > KLINE_STALE_MS) continue;
            if (closed.size() < n) continue;

            List<KlineData> win = closed.subList(closed.size() - n, closed.size());
            boolean allUp = true;
            for (KlineData k : win) {
                if (k.getClosePrice() <= k.getOpenPrice()) {
                    allUp = false;
                    break;
                }
            }
            if (!allUp) continue;

            KlineData first = win.get(0);
            KlineData last = win.get(win.size() - 1);
            long windowStart = first.getOpenTime();
            long windowEnd = last.getCloseTime();

            List<OpenInterestData> oiList = oiMap.getOrDefault(symbol, List.of());
            OpenInterestData oiStart = nearest(oiList, windowStart, OI_TOLERANCE_MS);
            OpenInterestData oiEnd = nearest(oiList, windowEnd, OI_TOLERANCE_MS);
            if (oiEnd == null) {
                oiEnd = latestBetween(oiList, windowStart + OI_MIN_SPAN_MS, startedAt);
                if (oiEnd != null && Math.abs(windowEnd - oiEnd.getTimestamp()) > OI_FALLBACK_MAX_MS) oiEnd = null;
            }
            if (oiStart == null || oiEnd == null || oiEnd.getTimestamp() <= oiStart.getTimestamp()) {
                skippedNoOi++;
                continue;
            }

            double startOi = sumOpenInterest(oiStart);
            double endOi = sumOpenInterest(oiEnd);
            if (startOi <= 0 || endOi <= startOi) continue;

            double price = last.getClosePrice();
            double firstOpen = first.getOpenPrice();
            double priceChange = firstOpen <= 0 ? 0 : price / firstOpen - 1;
            int qvFrom = Math.max(0, closed.size() - 24);
            double quoteVolume24h = 0;
            for (KlineData k : closed.subList(qvFrom, closed.size())) {
                quoteVolume24h += k.getQuoteVolume();
            }

            matches.add(new Match(symbol, price, priceChange, endOi / startOi - 1, n,
                    windowStart, windowEnd, oiStart.getTimestamp(), oiEnd.getTimestamp(), quoteVolume24h));
        }

        matches.sort(Comparator.comparingDouble(Match::oiChangePercent).reversed()
                .thenComparing(Comparator.comparingDouble(Match::priceChangePercent).reversed()));

        ScanResult result = new ScanResult(startedAt, symbols.size(), matches.size(), n,
                skippedNoOi, List.copyOf(matches));
        slots.put(n, result);
        log.info("hourly-rise-oi scan: symbols={} matched={} skippedNoOi={} periods={} elapsed={}ms",
                symbols.size(), matches.size(), skippedNoOi, n, System.currentTimeMillis() - startedAt);
        return result;
    }

    /** 只解析 sum_open_interest（币数）；空白或非法返回 0，调用方据此跳过。 */
    static double sumOpenInterest(OpenInterestData oi) {
        String v = oi.getSumOpenInterest();
        if (v == null || v.isBlank()) return 0;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 取时间戳距 target 最近、且不超过 tolerance 的记录；没有则返回 null。 */
    private static OpenInterestData nearest(List<OpenInterestData> oi, long target, long tolerance) {
        OpenInterestData best = null;
        long bestDiff = Long.MAX_VALUE;
        for (OpenInterestData o : oi) {
            long diff = Math.abs(o.getTimestamp() - target);
            if (diff <= tolerance && diff < bestDiff) {
                best = o;
                bestDiff = diff;
            }
        }
        return best;
    }

    /** 取 [minTs, maxTs] 内时间戳最大的记录（结束点还没同步时的退化路径）。 */
    private static OpenInterestData latestBetween(List<OpenInterestData> oi, long minTs, long maxTs) {
        OpenInterestData best = null;
        for (OpenInterestData o : oi) {
            long t = o.getTimestamp();
            if (t >= minTs && t <= maxTs && (best == null || t > best.getTimestamp())) best = o;
        }
        return best;
    }
}
