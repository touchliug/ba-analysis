package com.ba.analyzer.service;

import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.model.OpenInterestData;
import com.ba.analyzer.model.PremiumIndexData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据读取服务 (ba-analysis 专用) —— 只从MySQL读, 绝不访问币安。
 *
 * 数据的拉取与写入由独立的同步服务(ba-sync)负责; 本服务读到什么就分析什么,
 * 读不到(同步服务尚未跑/数据缺失)则返回空, 由调用方自行处理。
 * 分析器现仅消费日线级数据 (日K/日线OI) 与多空比; 5m 日内链路已随
 * 短期急涨/综合评分分析器一并移除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataFetchService {

    private final JdbcDataStore dataStore;

    private static final long DAY_MS = 86_400_000L;
    private static final long HOUR_MS = 3_600_000L;
    /** 批量 K 线缓存有效期: 一批分析(多个分析器串行)几秒内跑完, 60s 足够覆盖且不会明显过时。 */
    private static final long KLINE_CACHE_TTL_MS = 60_000L;

    /**
     * 按周期缓存最近一次批量 K 线查询。各分析器请求的根数不同, 缓存住"取过的最大根数",
     * 小请求直接切片复用; 1d 与 1h 各自一份, 互不覆盖。
     */
    private record CachedKlines(long at, int fetchedLimit, List<String> symbols,
                                Map<String, List<KlineData>> data) {}

    private final Map<String, CachedKlines> klineCaches = new ConcurrentHashMap<>();

    public Map<String, List<KlineData>> fetchDailyKlines(List<String> symbols, int days) {
        return fetchKlines(symbols, "1d", days);
    }

    /** 按周期批量取最近 limit 根 K 线 (1d/1h)。 */
    public Map<String, List<KlineData>> fetchKlines(List<String> symbols, String interval, int limit) {
        if (symbols == null || symbols.isEmpty()) return Map.of();
        long now = System.currentTimeMillis();
        int need = limit + 1;

        CachedKlines c = klineCaches.get(interval);
        if (c != null && now - c.at() < KLINE_CACHE_TTL_MS
                && c.fetchedLimit() >= need && c.symbols().equals(symbols)) {
            return trimKlinesByPeriod(c.data(), limit);
        }

        // 时间下界: 只取需要的区间(多留 10 根余量吸收停牌/缺口), 避免窗口函数扫该币全部历史。
        long since = now - (long) (need + 10) * intervalMillis(interval);
        Map<String, List<KlineData>> stored = dataStore.getKlinesBatch(symbols, interval, need, since);
        if (stored.size() < symbols.size() * 0.9) {
            log.warn("DB has {} klines for only {}/{} symbols; sync service may be behind",
                    interval, stored.size(), symbols.size());
        }
        klineCaches.put(interval, new CachedKlines(now, need, symbols, stored));
        return trimKlinesByPeriod(stored, limit);
    }

    public Map<String, List<OpenInterestData>> fetchOpenInterestHistoryBatch(List<String> symbols, int days) {
        return fetchOpenInterestHistoryBatch(symbols, "1d", days);
    }

    /** 按周期批量取 OI (1d/1h)。 */
    public Map<String, List<OpenInterestData>> fetchOpenInterestHistoryBatch(
            List<String> symbols, String period, int limit) {
        if (symbols == null || symbols.isEmpty()) return Map.of();
        Map<String, List<OpenInterestData>> stored =
                dataStore.getOpenInterestBatch(symbols, period, limit);
        if (stored.size() < symbols.size() * 0.9) {
            log.warn("DB has {} OI for only {}/{} symbols; sync service may be behind",
                    period, stored.size(), symbols.size());
        }
        return stored;
    }

    /** 1d / 1h 周期对应的毫秒数。 */
    public static long intervalMillis(String interval) {
        return switch (interval) {
            case "1d" -> DAY_MS;
            case "1h" -> HOUR_MS;
            default -> throw new IllegalArgumentException("Unsupported interval: " + interval);
        };
    }

    /** 批量读多空比 (供分析器)。缺失不放入 map; 覆盖率 <90% 时打 warn。 */
    public Map<String, List<LongShortRatioData>> getLongShortRatioBatch(
            List<String> symbols, String ratioType, String period, int limit) {
        Map<String, List<LongShortRatioData>> stored =
                dataStore.getLongShortRatioBatch(symbols, ratioType, period, limit);
        if (stored.size() < symbols.size() * 0.9) {
            log.warn("DB has {} LSR for only {}/{} symbols; sync service may be behind",
                    ratioType, stored.size(), symbols.size());
        }
        return stored;
    }

    /** 各合约最新资金费率 (premium_index 快照, 小数形式如 0.0001 = 0.01%)。 */
    public Map<String, Double> getLastFundingRates() {
        Map<String, Double> rates = new HashMap<>();
        for (PremiumIndexData p : dataStore.getAllPremiumIndex()) {
            if (p.getSymbol() != null) rates.put(p.getSymbol(), p.getLastFundingRateNum());
        }
        return rates;
    }

    private Map<String, List<KlineData>> trimKlinesByPeriod(Map<String, List<KlineData>> klineMap, int period) {
        Map<String, List<KlineData>> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            List<KlineData> klines = entry.getValue();
            if (klines.size() <= period) {
                result.put(entry.getKey(), klines);
            } else {
                result.put(entry.getKey(), klines.subList(klines.size() - period, klines.size()));
            }
        }
        return result;
    }
}
