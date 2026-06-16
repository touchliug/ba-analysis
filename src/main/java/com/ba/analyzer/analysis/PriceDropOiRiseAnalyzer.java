package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PriceDropOiRiseAnalyzer extends AbstractKlineAnalyzer {

    public PriceDropOiRiseAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "价涨仓增";
    }

    @Override
    public String getDescription() {
        var cfg = appProperties.getAnalysis().getPriceDropOiRise();
        return String.format("最近%d天价格上涨不超过%.0f%%但持仓量增长超过%.0f%%的币",
                cfg.getDays(), cfg.getMaxPriceRisePercent(), cfg.getMinOiRisePercent());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getPriceDropOiRise().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getPriceDropOiRise();
        return createParams("days", cfg.getDays(),
                "maxPriceRisePercent", cfg.getMaxPriceRisePercent(),
                "minOiRisePercent", cfg.getMinOiRisePercent(),
                "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getPriceDropOiRise();
        int days = getIntParam(requestParams, "days", cfg.getDays());
        double maxPriceRise = getDoubleParam(requestParams, "maxPriceRisePercent", cfg.getMaxPriceRisePercent());
        double minOiRise = getDoubleParam(requestParams, "minOiRisePercent", cfg.getMinOiRisePercent());
        double vr = getDoubleParam(requestParams, "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
        return createParams("days", days, "maxPriceRisePercent", maxPriceRise,
                "minOiRisePercent", minOiRise, "volumeSurgeRatio", vr);
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        List<String> symbols = new ArrayList<>(klineMap.keySet());
        Map<String, List<OpenInterestData>> oiHistoryMap = dataFetchService.fetchOpenInterestHistoryBatch(symbols, days);
        return doAnalyzeWithOi(klineMap, oiHistoryMap, params);
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("最近%d天价格上涨不超过%.0f%%但持仓量增长超过%.0f%%的币",
                (int) params.get("days"), (double) params.get("maxPriceRisePercent"),
                (double) params.get("minOiRisePercent"));
    }

    private List<AnalysisReport.CoinAnalysis> doAnalyzeWithOi(
            Map<String, List<KlineData>> klineMap,
            Map<String, List<OpenInterestData>> oiHistoryMap,
            Map<String, Object> params) {
        int days = (int) params.get("days");
        double maxPriceRise = (double) params.get("maxPriceRisePercent");
        double minOiRise = (double) params.get("minOiRisePercent");
        double volumeSurgeRatio = (double) params.get("volumeSurgeRatio");
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < days) continue;

            List<KlineData> recentKlines = klines.subList(klines.size() - days, klines.size());
            double totalRise = recentKlines.stream().mapToDouble(KlineData::getChangePercent).sum();
            if (totalRise > maxPriceRise) continue;

            List<OpenInterestData> oiHistory = oiHistoryMap.get(symbol);
            if (oiHistory == null || oiHistory.isEmpty()) continue;

            double firstOi = oiHistory.get(0).getOpenInterestValue();
            double lastOi = oiHistory.get(oiHistory.size() - 1).getOpenInterestValue();
            if (firstOi <= 0) continue;

            double oiChangePercent = ((lastOi - firstOi) / firstOi) * 100;
            if (oiChangePercent < minOiRise) continue;

            List<KlineData> histForVol = klines.size() > days ? klines.subList(0, klines.size() - days) : List.of();
            double vr = calculateVolumeRatio(recentKlines, histForVol);
            if (volumeSurgeRatio > 1.0 && vr < volumeSurgeRatio) continue;

            KlineData latest = recentKlines.get(recentKlines.size() - 1);

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(latest.getClosePrice())
                    .changePercent(totalRise)
                    .detail(String.format("%d天内价格上涨%.2f%%，持仓量从%.2f增至%.2f(%.2f%%)，成交量%.1f倍",
                            days, totalRise, firstOi, lastOi, oiChangePercent, vr))
                    .build());
        }
        return matched;
    }
}
