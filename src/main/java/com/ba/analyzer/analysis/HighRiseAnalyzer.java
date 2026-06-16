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
public class HighRiseAnalyzer extends AbstractKlineAnalyzer {

    public HighRiseAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "大幅上涨";
    }

    @Override
    public String getDescription() {
        var cfg = appProperties.getAnalysis().getHighRise();
        return String.format("最近%d天内上涨超过%.0f%%的币", cfg.getDays(), cfg.getThresholdPercent());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getHighRise().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getHighRise();
        return createParams("days", cfg.getDays(),
                "thresholdPercent", cfg.getThresholdPercent(),
                "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getHighRise();
        int days = getIntParam(requestParams, "days", cfg.getDays());
        double threshold = getDoubleParam(requestParams, "thresholdPercent", cfg.getThresholdPercent());
        double vr = getDoubleParam(requestParams, "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
        return createParams("days", days, "thresholdPercent", threshold, "volumeSurgeRatio", vr);
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("最近%d天内上涨超过%.0f%%的币",
                (int) params.get("days"), (double) params.get("thresholdPercent"));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        double threshold = (double) params.get("thresholdPercent");
        double volumeSurgeRatio = (double) params.get("volumeSurgeRatio");
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < 2) continue;

            List<KlineData> recentKlines = klines.size() > days
                    ? klines.subList(klines.size() - days, klines.size()) : klines;
            double startPrice = recentKlines.get(0).getOpenPrice();
            double endPrice = recentKlines.get(recentKlines.size() - 1).getClosePrice();
            if (startPrice <= 0) continue;

            double changePercent = ((endPrice - startPrice) / startPrice) * 100;
            if (changePercent < threshold) continue;

            List<KlineData> historyKlines = klines.size() > days
                    ? klines.subList(0, klines.size() - days) : List.of();
            double vr = calculateVolumeRatio(recentKlines, historyKlines);
            if (volumeSurgeRatio > 1.0 && vr < volumeSurgeRatio) continue;

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(endPrice)
                    .changePercent(changePercent)
                    .detail(String.format("%d天内从%.4f涨到%.4f，涨幅%.2f%%，成交量为历史%.1f倍",
                            days, startPrice, endPrice, changePercent, vr))
                    .build());
        }
        return matched;
    }
}
