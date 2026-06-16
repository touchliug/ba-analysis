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
public class ConsecutiveRiseAnalyzer extends AbstractKlineAnalyzer {

    public ConsecutiveRiseAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "连续上涨";
    }

    @Override
    public String getDescription() {
        int days = appProperties.getAnalysis().getConsecutiveRise().getDays();
        return String.format("连续%d天上涨的币", days);
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getConsecutiveRise().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getConsecutiveRise();
        return createParams("days", cfg.getDays(), "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getConsecutiveRise();
        int days = getIntParam(requestParams, "days", cfg.getDays());
        double vr = getDoubleParam(requestParams, "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
        return createParams("days", days, "volumeSurgeRatio", vr);
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("连续%d天上涨的币", (int) params.get("days"));
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
            if (klines.size() < days) continue;

            List<KlineData> recentKlines = klines.subList(klines.size() - days, klines.size());
            boolean allRise = recentKlines.stream().allMatch(k -> k.getChangePercent() > 0);
            if (!allRise) continue;

            List<KlineData> historyKlines = klines.subList(0, klines.size() - days);
            double vr = calculateVolumeRatio(recentKlines, historyKlines);
            if (volumeSurgeRatio > 1.0 && vr < volumeSurgeRatio) continue;

            double totalChange = recentKlines.stream().mapToDouble(KlineData::getChangePercent).sum();
            KlineData latest = recentKlines.get(recentKlines.size() - 1);

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(latest.getClosePrice())
                    .changePercent(totalChange)
                    .detail(String.format("连续%d天上涨，累计涨幅%.2f%%，成交量为历史%.1f倍",
                            days, totalChange, vr))
                    .build());
        }
        return matched;
    }
}
