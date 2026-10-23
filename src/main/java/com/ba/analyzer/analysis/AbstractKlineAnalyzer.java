package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractKlineAnalyzer implements Analyzer {

    protected static final long DAY_MS = 86_400_000L;

    protected final DataFetchService dataFetchService;
    protected final AppProperties appProperties;

    protected AbstractKlineAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        this.dataFetchService = dataFetchService;
        this.appProperties = appProperties;
    }

    @Override
    public AnalysisReport analyze(List<String> symbols) {
        Map<String, Object> usedParams = getDefaultParams();
        usedParams.put("source", "config");
        return executeAnalysis(symbols, usedParams);
    }

    @Override
    public AnalysisReport analyze(List<String> symbols, Map<String, Object> params) {
        Map<String, Object> usedParams = resolveParams(params);
        usedParams.put("source", "request");
        return executeAnalysis(symbols, usedParams);
    }

    protected abstract Map<String, Object> getDefaultParams();

    protected abstract Map<String, Object> resolveParams(Map<String, Object> requestParams);

    protected abstract List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params);

    protected abstract String buildDescription(Map<String, Object> params);

    /**
     * 是否只把已收盘日K交给 doAnalyze。默认 true: 去掉当前未收盘根(closeTime 在未来),
     * 并跳过最新已收盘根超过1天的币(日线同步滞后, 信号会错位)。
     * 需要当日开盘价作入场价的分析器(首阴日/反转做多)覆盖为 false 自行处理。
     */
    protected boolean closedCandlesOnly() {
        return true;
    }

    protected int getHistoryBufferDays() {
        return appProperties.getConcurrency().getHistoryBufferDays();
    }

    protected AnalysisReport executeAnalysis(List<String> symbols, Map<String, Object> params) {
        Map<String, List<KlineData>> klineMap = fetchData(symbols, params);
        if (closedCandlesOnly()) klineMap = toClosedCandles(klineMap, System.currentTimeMillis());
        List<AnalysisReport.CoinAnalysis> matched = doAnalyze(klineMap, params);
        sortResults(matched);
        return buildReport(symbols.size(), matched, params);
    }

    /** +1: 当前未收盘根会被 toClosedCandles 去掉, 多取一根补足。 */
    protected Map<String, List<KlineData>> fetchData(List<String> symbols, Map<String, Object> params) {
        int days = (int) params.getOrDefault("days", 7);
        return dataFetchService.fetchDailyKlines(symbols, days + getHistoryBufferDays() + 1);
    }

    static Map<String, List<KlineData>> toClosedCandles(Map<String, List<KlineData>> klineMap, long now) {
        Map<String, List<KlineData>> result = new HashMap<>();
        for (Map.Entry<String, List<KlineData>> e : klineMap.entrySet()) {
            List<KlineData> klines = e.getValue();
            if (klines == null || klines.isEmpty()) continue;
            int end = klines.size();
            if (klines.get(end - 1).getCloseTime() > now) end--;
            if (end == 0) continue;
            long lastClose = klines.get(end - 1).getCloseTime();
            if (lastClose > 0 && now - lastClose > DAY_MS) continue;
            result.put(e.getKey(), klines.subList(0, end));
        }
        return result;
    }

    protected void sortResults(List<AnalysisReport.CoinAnalysis> matched) {
        matched.sort((a, b) -> Double.compare(b.getChangePercent(), a.getChangePercent()));
    }

    protected AnalysisReport buildReport(int totalAnalyzed,
            List<AnalysisReport.CoinAnalysis> matched, Map<String, Object> params) {
        return AnalysisReport.builder()
                .analysisType(getName())
                .analysisTime(LocalDateTime.now())
                .description(buildDescription(params))
                .coins(matched)
                .totalAnalyzed(totalAnalyzed)
                .matchedCount(matched.size())
                .usedParams(params)
                .build();
    }

    protected double calculateVolumeRatio(List<KlineData> recent, List<KlineData> history) {
        double recentAvg = recent.stream().mapToDouble(KlineData::getQuoteVolume).average().orElse(0);
        double histAvg = history.stream().mapToDouble(KlineData::getQuoteVolume).average().orElse(1);
        return histAvg > 0 ? recentAvg / histAvg : 1.0;
    }

    protected Map<String, Object> createParams(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    protected static boolean getBoolParam(Map<String, Object> params, String key, boolean defaultValue) {
        if (params == null || !params.containsKey(key)) return defaultValue;
        Object val = params.get(key);
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }
}
