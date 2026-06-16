package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class FirstYinDayAnalyzer extends AbstractKlineAnalyzer {

    public FirstYinDayAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "首阴日";
    }

    @Override
    public String getDescription() {
        var cfg = appProperties.getAnalysis().getFirstYinDay();
        return String.format("连续%d天大涨(累计≥%.0f%%)后首日出现阴线且跌破前日收盘的币",
                cfg.getDays(), cfg.getMinTotalRisePercent());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getFirstYinDay().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getFirstYinDay();
        return createParams("days", cfg.getDays(),
                "minTotalRisePercent", cfg.getMinTotalRisePercent(),
                "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getFirstYinDay();
        int days = getIntParam(requestParams, "days", cfg.getDays());
        double minTotalRise = getDoubleParam(requestParams, "minTotalRisePercent", cfg.getMinTotalRisePercent());
        double vr = getDoubleParam(requestParams, "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
        return createParams("days", days, "minTotalRisePercent", minTotalRise, "volumeSurgeRatio", vr);
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
        return String.format("连续%d天大涨(累计≥%.0f%%)后首日出现阴线且跌破前日收盘的币",
                (int) params.get("days"), (double) params.get("minTotalRisePercent"));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        double minTotalRise = (double) params.get("minTotalRisePercent");
        double volumeSurgeRatio = (double) params.get("volumeSurgeRatio");
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < days + 1) continue;

            KlineData today = klines.get(klines.size() - 1);
            KlineData yesterday = klines.get(klines.size() - 2);
            List<KlineData> prevDays = klines.subList(klines.size() - 1 - days, klines.size() - 1);

            boolean prevAllRise = prevDays.stream().allMatch(k -> k.getChangePercent() > 0);
            boolean todayBearish = today.getClosePrice() < today.getOpenPrice();
            if (!prevAllRise || !todayBearish) continue;

            double totalRise = prevDays.stream().mapToDouble(KlineData::getChangePercent).sum();
            if (totalRise < minTotalRise) continue;

            if (today.getClosePrice() >= yesterday.getClosePrice()) continue;

            double prevAvgVolume = prevDays.stream().mapToDouble(KlineData::getQuoteVolume).average().orElse(1);
            double vr = prevAvgVolume > 0 ? today.getQuoteVolume() / prevAvgVolume : 1.0;
            if (volumeSurgeRatio > 1.0 && vr < volumeSurgeRatio) continue;

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(today.getClosePrice())
                    .changePercent(today.getChangePercent())
                    .detail(String.format(
                            "前%d天累计涨%.2f%%≥%.0f%%，今日首阴跌%.2f%%，收盘%.4f<昨收%.4f，放量%.1f倍",
                            days, totalRise, minTotalRise, today.getChangePercent(),
                            today.getClosePrice(), yesterday.getClosePrice(), vr))
                    .build());
        }
        return matched;
    }
}
