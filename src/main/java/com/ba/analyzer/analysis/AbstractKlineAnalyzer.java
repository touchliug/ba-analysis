package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractKlineAnalyzer implements Analyzer {

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

    protected int getHistoryBufferDays() {
        return appProperties.getConcurrency().getHistoryBufferDays();
    }

    protected AnalysisReport executeAnalysis(List<String> symbols, Map<String, Object> params) {
        Map<String, List<KlineData>> klineMap = fetchData(symbols, params);
        List<AnalysisReport.CoinAnalysis> matched = doAnalyze(klineMap, params);
        sortResults(matched);
        return buildReport(symbols.size(), matched, params);
    }

    protected Map<String, List<KlineData>> fetchData(List<String> symbols, Map<String, Object> params) {
        int days = (int) params.getOrDefault("days", 7);
        return dataFetchService.fetchDailyKlines(symbols, days + getHistoryBufferDays());
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
}
