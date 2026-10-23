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

    public Map<String, List<KlineData>> fetchDailyKlines(List<String> symbols, int days) {
        Map<String, List<KlineData>> stored = dataStore.getKlinesBatch(symbols, "1d", days + 1);
        if (stored.size() < symbols.size() * 0.9) {
            log.warn("DB has 1d klines for only {}/{} symbols; sync service may be behind",
                    stored.size(), symbols.size());
        }
        return trimKlinesByPeriod(stored, days);
    }

    public Map<String, List<OpenInterestData>> fetchOpenInterestHistoryBatch(List<String> symbols, int days) {
        Map<String, List<OpenInterestData>> stored = dataStore.getOpenInterestBatch(symbols, "1d", days);
        if (stored.size() < symbols.size() * 0.9) {
            log.warn("DB has 1d OI for only {}/{} symbols; sync service may be behind",
                    stored.size(), symbols.size());
        }
        return stored;
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
