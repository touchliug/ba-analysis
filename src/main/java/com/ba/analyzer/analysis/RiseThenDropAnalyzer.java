package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RiseThenDropAnalyzer extends AbstractKlineAnalyzer {

    public RiseThenDropAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "连涨今跌";
    }

    @Override
    public String getDescription() {
        int days = appProperties.getAnalysis().getRiseThenDrop().getDays();
        return String.format("连续%d天上涨但今天下跌的币", days);
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getRiseThenDrop().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getRiseThenDrop();
        return createParams("days", cfg.getDays(), "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getRiseThenDrop();
        int days = getIntParam(requestParams, "days", cfg.getDays());
        double vr = getDoubleParam(requestParams, "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
        return createParams("days", days, "volumeSurgeRatio", vr);
    }

    @Override
    protected Map<String, List<KlineData>> fetchData(List<String> symbols, Map<String, Object> params) {
        int days = (int) params.get("days");
        return dataFetchService.fetchDailyKlines(symbols, days + 1 + getHistoryBufferDays());
    }

    @Override
    protected void sortResults(List<AnalysisReport.CoinAnalysis> matched) {
        matched.sort((a, b) -> Double.compare(a.getChangePercent(), b.getChangePercent()));
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("连续%d天上涨但今天下跌的币", (int) params.get("days"));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        double volumeSurgeRatio = (double) params.get("volumeSurgeRatio");
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < days + 1) continue;

            KlineData today = klines.get(klines.size() - 1);
            List<KlineData> prevDays = klines.subList(klines.size() - 1 - days, klines.size() - 1);

            boolean prevAllRise = prevDays.stream().allMatch(k -> k.getChangePercent() > 0);
            boolean todayDrop = today.getChangePercent() < 0;
            if (!prevAllRise || !todayDrop) continue;

            double prevAvgVolume = prevDays.stream().mapToDouble(KlineData::getQuoteVolume).average().orElse(1);
            double vr = prevAvgVolume > 0 ? today.getQuoteVolume() / prevAvgVolume : 1.0;
            if (volumeSurgeRatio > 1.0 && vr < volumeSurgeRatio) continue;

            double totalRise = prevDays.stream().mapToDouble(KlineData::getChangePercent).sum();

            // Format dates
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
            String riseStart = java.time.LocalDateTime.ofEpochSecond(
                    prevDays.get(0).getOpenTime() / 1000, 0, ZoneOffset.ofHours(8)).format(fmt);
            String riseEnd = java.time.LocalDateTime.ofEpochSecond(
                    prevDays.get(prevDays.size() - 1).getOpenTime() / 1000, 0, ZoneOffset.ofHours(8)).format(fmt);
            String dropDate = java.time.LocalDateTime.ofEpochSecond(
                    today.getOpenTime() / 1000, 0, ZoneOffset.ofHours(8)).format(fmt);

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(today.getClosePrice())
                    .changePercent(today.getChangePercent())
                    .detail(String.format("连涨%d天(%s~%s)累计+%.2f%%，%s跌%.2f%%，放量%.1f倍",
                            days, riseStart, riseEnd, totalRise, dropDate, today.getChangePercent(), vr))
                    .build());
        }
        return matched;
    }
}
