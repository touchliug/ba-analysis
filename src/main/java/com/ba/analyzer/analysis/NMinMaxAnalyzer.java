package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NMinMaxAnalyzer implements Analyzer {

    private final DataFetchService dataFetchService;
    private final AppProperties appProperties;

    @Override
    public String getName() {
        return "N日高低点";
    }

    @Override
    public String getDescription() {
        int days = appProperties.getAnalysis().getNMinMax().getDays();
        return String.format("获取所有合约最近%d天的最低价和最高价", days);
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getNMinMax().isEnabled();
    }

    @Override
    public AnalysisReport analyze(List<String> symbols) {
        AppProperties.NMinMaxConfig config = appProperties.getAnalysis().getNMinMax();
        int days = config.getDays();
        Map<String, Object> usedParams = new LinkedHashMap<>();
        usedParams.put("days", days);
        usedParams.put("source", "config");
        return doAnalyze(symbols, days, usedParams);
    }

    @Override
    public AnalysisReport analyze(List<String> symbols, Map<String, Object> params) {
        int defaultDays = appProperties.getAnalysis().getNMinMax().getDays();
        int days = getIntParam(params, "days", defaultDays);
        Map<String, Object> usedParams = new LinkedHashMap<>();
        usedParams.put("days", days);
        usedParams.put("source", params != null && params.containsKey("days") ? "request" : "config");
        return doAnalyze(symbols, days, usedParams);
    }

    private AnalysisReport doAnalyze(List<String> symbols, int days, Map<String, Object> usedParams) {
        Map<String, List<KlineData>> klineMap = dataFetchService.fetchDailyKlines(symbols, days);

        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("MM-dd");
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();

            if (klines.isEmpty()) continue;

            List<KlineData> recentKlines = klines.size() > days
                    ? klines.subList(klines.size() - days, klines.size())
                    : klines;

            KlineData minBar = recentKlines.stream().min(Comparator.comparingDouble(KlineData::getLowPrice)).orElse(null);
            KlineData maxBar = recentKlines.stream().max(Comparator.comparingDouble(KlineData::getHighPrice)).orElse(null);
            double latestPrice = recentKlines.get(recentKlines.size() - 1).getClosePrice();

            if (minBar == null || maxBar == null) continue;

            double minPrice = minBar.getLowPrice();
            double maxPrice = maxBar.getHighPrice();
            String minDate = formatDate(minBar.getOpenTime(), dateFmt, zone);
            String maxDate = formatDate(maxBar.getOpenTime(), dateFmt, zone);

            double positionPercent = (maxPrice - minPrice) > 0
                    ? ((latestPrice - minPrice) / (maxPrice - minPrice)) * 100
                    : 50;

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(latestPrice)
                    .changePercent(positionPercent)
                    .detail(String.format("%d日最低%.6f(%s) 最高%.6f(%s) 现价%.6f 位置%.1f%%",
                            days, minPrice, minDate, maxPrice, maxDate, latestPrice, positionPercent))
                    .build());
        }

        matched.sort((a, b) -> Double.compare(b.getChangePercent(), a.getChangePercent()));

        return AnalysisReport.builder()
                .analysisType(getName())
                .analysisTime(LocalDateTime.now())
                .description(String.format("所有合约最近%d天的最低价和最高价", days))
                .coins(matched)
                .totalAnalyzed(symbols.size())
                .matchedCount(matched.size())
                .usedParams(usedParams)
                .build();
    }

    private String formatDate(long epochMs, DateTimeFormatter fmt, ZoneId zone) {
        return Instant.ofEpochMilli(epochMs).atZone(zone).format(fmt);
    }
}
