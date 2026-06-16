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
public class OiConsecutiveRiseAnalyzer extends AbstractKlineAnalyzer {

    public OiConsecutiveRiseAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "持仓量连增";
    }

    @Override
    public String getDescription() {
        var cfg = appProperties.getAnalysis().getOiConsecutiveRise();
        return String.format("持仓量连续%d天上涨且价格涨幅不超过%.0f%%的币",
                cfg.getDays(), cfg.getMaxPriceRisePercent());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getOiConsecutiveRise().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getOiConsecutiveRise();
        return createParams("days", cfg.getDays(),
                "maxPriceRisePercent", cfg.getMaxPriceRisePercent(),
                "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getOiConsecutiveRise();
        int days = getIntParam(requestParams, "days", cfg.getDays());
        double maxPriceRise = getDoubleParam(requestParams, "maxPriceRisePercent", cfg.getMaxPriceRisePercent());
        double vr = getDoubleParam(requestParams, "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
        return createParams("days", days, "maxPriceRisePercent", maxPriceRise, "volumeSurgeRatio", vr);
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        double maxPriceRise = (double) params.get("maxPriceRisePercent");
        double volumeSurgeRatio = (double) params.get("volumeSurgeRatio");

        List<String> symbols = new ArrayList<>(klineMap.keySet());
        Map<String, List<OpenInterestData>> oiHistoryMap =
                dataFetchService.fetchOpenInterestHistoryBatch(symbols, days + 1);
        return doAnalyzeWithOi(klineMap, oiHistoryMap, days, maxPriceRise, volumeSurgeRatio);
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("持仓量连续%d天上涨且价格涨幅不超过%.0f%%的币",
                (int) params.get("days"), (double) params.get("maxPriceRisePercent"));
    }

    private List<AnalysisReport.CoinAnalysis> doAnalyzeWithOi(
            Map<String, List<KlineData>> klineMap,
            Map<String, List<OpenInterestData>> oiHistoryMap,
            int days, double maxPriceRise, double volumeSurgeRatio) {
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < days) continue;

            List<KlineData> recentKlines = klines.subList(klines.size() - days, klines.size());
            double startPrice = recentKlines.get(0).getOpenPrice();
            double endPrice = recentKlines.get(recentKlines.size() - 1).getClosePrice();
            if (startPrice <= 0) continue;

            double priceChange = ((endPrice - startPrice) / startPrice) * 100;
            if (priceChange > maxPriceRise) continue;

            List<OpenInterestData> oiHistory = oiHistoryMap.get(symbol);
            if (oiHistory == null || oiHistory.size() < days + 1) continue;

            List<OpenInterestData> recentOi = oiHistory.size() > days
                    ? oiHistory.subList(oiHistory.size() - days - 1, oiHistory.size())
                    : oiHistory;
            if (recentOi.size() < days + 1) continue;

            boolean oiConsecutiveRise = true;
            double oiFirst = recentOi.get(0).getOpenInterestValue();
            double oiLast = recentOi.get(recentOi.size() - 1).getOpenInterestValue();
            for (int i = 1; i < recentOi.size(); i++) {
                if (recentOi.get(i).getOpenInterestValue() <= recentOi.get(i - 1).getOpenInterestValue()) {
                    oiConsecutiveRise = false;
                    break;
                }
            }
            if (!oiConsecutiveRise) continue;

            List<KlineData> histForVol = klines.size() > days
                    ? klines.subList(0, klines.size() - days) : List.of();
            double vr = calculateVolumeRatio(recentKlines, histForVol);
            if (volumeSurgeRatio > 1.0 && vr < volumeSurgeRatio) continue;

            double oiTotalChange = oiFirst > 0 ? ((oiLast - oiFirst) / oiFirst) * 100 : 0;

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(endPrice)
                    .changePercent(priceChange)
                    .detail(String.format(
                            "持仓量连续%d天上涨，从%.0f增至%.0f(%.1f%%)，价格涨幅%.2f%%≤%.0f%%，成交量%.1f倍",
                            days, oiFirst, oiLast, oiTotalChange,
                            priceChange, maxPriceRise, vr))
                    .build());
        }
        return matched;
    }
}
