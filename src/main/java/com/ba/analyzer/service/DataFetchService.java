package com.ba.analyzer.service;

import com.ba.analyzer.model.FundingRateData;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.model.OpenInterestData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据读取服务 (ba-analysis 专用) —— 只从MySQL读, 绝不访问币安。
 *
 * 保留与原 DataFetchService 相同的方法签名, 使20个分析器与 MarketFeatureService 无需改动。
 * 数据的拉取与写入由独立的同步服务(ba-sync)负责; 本服务读到什么就分析什么,
 * 读不到(同步服务尚未跑/数据缺失)则返回空, 由调用方自行处理。
 *
 * 注意: 不再有"缓存未命中则拉API"的回退逻辑 —— 那是同步服务的职责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataFetchService {

    private final JdbcDataStore dataStore;

    public Map<String, List<KlineData>> fetchDailyKlines(List<String> symbols, int days) {
        return fetchKlinesByInterval(symbols, "1d", days);
    }

    public Map<String, List<KlineData>> fetchKlinesByInterval(List<String> symbols, String interval, int period) {
        Map<String, List<KlineData>> stored = dataStore.getKlinesBatch(symbols, interval, period + 1);
        if (stored.size() < symbols.size() * 0.9) {
            log.warn("DB has {} klines for only {}/{} symbols; sync service may be behind",
                    interval, stored.size(), symbols.size());
        }
        return trimKlinesByPeriod(stored, period);
    }

    public Map<String, List<OpenInterestData>> fetchOpenInterestHistoryBatch(List<String> symbols, int days) {
        return fetchOiHistoryByPeriod(symbols, "1d", days);
    }

    public Map<String, List<OpenInterestData>> fetchOiHistoryByPeriod(List<String> symbols, String period, int limit) {
        Map<String, List<OpenInterestData>> stored = dataStore.getOpenInterestBatch(symbols, period, limit);
        if (stored.size() < symbols.size() * 0.9) {
            log.warn("DB has {} OI for only {}/{} symbols; sync service may be behind",
                    period, stored.size(), symbols.size());
        }
        return stored;
    }

    /** 从DB读取最近的资金费率 (供特征引擎使用)。 */
    public List<FundingRateData> getRecentFundingRates(String symbol, int limit) {
        return dataStore.getFundingRates(symbol, limit);
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
