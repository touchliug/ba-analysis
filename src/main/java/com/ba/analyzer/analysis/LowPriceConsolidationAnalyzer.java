package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class LowPriceConsolidationAnalyzer extends AbstractKlineAnalyzer {

    public LowPriceConsolidationAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "低位插针";
    }

    @Override
    public String getDescription() {
        var cfg = appProperties.getAnalysis().getLowPriceConsolidation();
        return String.format("最近%d天价格上下插针但变化不超过%.0f%%且处于低位的币",
                cfg.getDays(), cfg.getMaxPriceChangePercent());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getLowPriceConsolidation().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getLowPriceConsolidation();
        return createParams("days", cfg.getDays(),
                "maxPriceChangePercent", cfg.getMaxPriceChangePercent(),
                "lowPricePercentile", cfg.getLowPricePercentile(),
                "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getLowPriceConsolidation();
        int days = getIntParam(requestParams, "days", cfg.getDays());
        double maxPriceChange = getDoubleParam(requestParams, "maxPriceChangePercent", cfg.getMaxPriceChangePercent());
        double lowPercentile = getDoubleParam(requestParams, "lowPricePercentile", cfg.getLowPricePercentile());
        double vr = getDoubleParam(requestParams, "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
        return createParams("days", days, "maxPriceChangePercent", maxPriceChange,
                "lowPricePercentile", lowPercentile, "volumeSurgeRatio", vr);
    }

    @Override
    protected void sortResults(List<AnalysisReport.CoinAnalysis> matched) {
        matched.sort(Comparator.comparingDouble(AnalysisReport.CoinAnalysis::getChangePercent));
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("最近%d天价格上下插针但变化不超过%.0f%%且处于低位的币",
                (int) params.get("days"), (double) params.get("maxPriceChangePercent"));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        double maxPriceChange = (double) params.get("maxPriceChangePercent");
        double lowPercentile = (double) params.get("lowPricePercentile");
        double volumeSurgeRatio = (double) params.get("volumeSurgeRatio");
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < days) continue;

            List<KlineData> recentKlines = klines.subList(klines.size() - days, klines.size());
            double startPrice = recentKlines.get(0).getOpenPrice();
            double endPrice = recentKlines.get(recentKlines.size() - 1).getClosePrice();
            if (startPrice <= 0) continue;

            double overallChange = Math.abs(((endPrice - startPrice) / startPrice) * 100);

            boolean hasWicks = recentKlines.stream().anyMatch(k -> {
                double upperWick = k.getHighPrice() - Math.max(k.getOpenPrice(), k.getClosePrice());
                double lowerWick = Math.min(k.getOpenPrice(), k.getClosePrice()) - k.getLowPrice();
                double bodyRange = Math.abs(k.getClosePrice() - k.getOpenPrice());
                return bodyRange > 0 && (upperWick / bodyRange > 1.0 || lowerWick / bodyRange > 1.0);
            });

            boolean isLowPrice = checkLowPrice(klines, days, endPrice, lowPercentile);

            if (overallChange > maxPriceChange || !hasWicks || !isLowPrice) continue;

            double vr = calculateWickDayVolumeRatio(recentKlines);
            if (volumeSurgeRatio > 1.0 && vr < volumeSurgeRatio) continue;

            double maxHigh = recentKlines.stream().mapToDouble(KlineData::getHighPrice).max().orElse(0);
            double minLow = recentKlines.stream().mapToDouble(KlineData::getLowPrice).min().orElse(0);

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(endPrice)
                    .changePercent(((endPrice - startPrice) / startPrice) * 100)
                    .detail(String.format("%d天内价格变化%.2f%%，最高%.4f最低%.4f，处于低位，插针日放量%.1f倍",
                            days, overallChange, maxHigh, minLow, vr))
                    .build());
        }
        return matched;
    }

    private boolean checkLowPrice(List<KlineData> klines, int days, double endPrice, double lowPercentile) {
        if (klines.size() < 30) return true;
        List<KlineData> historyKlines = klines.subList(0, klines.size() - days);
        List<Double> historicalCloses = historyKlines.stream()
                .map(KlineData::getClosePrice).sorted().toList();
        int index = (int) (historicalCloses.size() * lowPercentile / 100);
        double threshold = historicalCloses.get(Math.min(index, historicalCloses.size() - 1));
        return endPrice <= threshold;
    }

    private double calculateWickDayVolumeRatio(List<KlineData> recentKlines) {
        double wickDayVolSum = 0;
        int wickDayCount = 0;
        double totalVolSum = 0;
        for (KlineData k : recentKlines) {
            double upperWick = k.getHighPrice() - Math.max(k.getOpenPrice(), k.getClosePrice());
            double lowerWick = Math.min(k.getOpenPrice(), k.getClosePrice()) - k.getLowPrice();
            double bodyRange = Math.abs(k.getClosePrice() - k.getOpenPrice());
            double qv = k.getQuoteVolume();
            totalVolSum += qv;
            if (bodyRange > 0 && (upperWick / bodyRange > 1.0 || lowerWick / bodyRange > 1.0)) {
                wickDayVolSum += qv;
                wickDayCount++;
            }
        }
        double avgVolPerDay = totalVolSum / recentKlines.size();
        double wickDayAvgVol = wickDayCount > 0 ? wickDayVolSum / wickDayCount : 0;
        return avgVolPerDay > 0 ? wickDayAvgVol / avgVolPerDay : 0;
    }
}
