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
public class SlowRiseAnalyzer extends AbstractKlineAnalyzer {

    public SlowRiseAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "缓涨币";
    }

    @Override
    public String getDescription() {
        var cfg = appProperties.getAnalysis().getSlowRise();
        return String.format("最近%d天一直在涨但每日涨幅不超过%.1f%%的币",
                cfg.getDays(), cfg.getMaxDailyChangePercent());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getSlowRise().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getSlowRise();
        return createParams("days", cfg.getDays(),
                "maxDailyChangePercent", cfg.getMaxDailyChangePercent(),
                "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getSlowRise();
        int days = getIntParam(requestParams, "days", cfg.getDays());
        double maxDailyChange = getDoubleParam(requestParams, "maxDailyChangePercent", cfg.getMaxDailyChangePercent());
        double vr = getDoubleParam(requestParams, "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
        return createParams("days", days, "maxDailyChangePercent", maxDailyChange, "volumeSurgeRatio", vr);
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("最近%d天一直在涨但每日涨幅不超过%.1f%%的币",
                (int) params.get("days"), (double) params.get("maxDailyChangePercent"));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        double maxDailyChange = (double) params.get("maxDailyChangePercent");
        double volumeSurgeRatio = (double) params.get("volumeSurgeRatio");
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < days) continue;

            List<KlineData> recentKlines = klines.subList(klines.size() - days, klines.size());
            boolean allRise = recentKlines.stream().allMatch(k -> k.getChangePercent() > 0);
            boolean allSmallChange = recentKlines.stream().allMatch(k -> k.getChangePercent() <= maxDailyChange);
            if (!allRise || !allSmallChange) continue;

            List<KlineData> historyKlines = klines.subList(0, klines.size() - days);
            double vr = calculateVolumeRatio(recentKlines, historyKlines);
            if (volumeSurgeRatio > 1.0 && vr > volumeSurgeRatio) continue;

            double totalChange = recentKlines.stream().mapToDouble(KlineData::getChangePercent).sum();
            KlineData latest = recentKlines.get(recentKlines.size() - 1);

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(latest.getClosePrice())
                    .changePercent(totalChange)
                    .detail(String.format("连续%d天缓涨，每日涨幅≤%.1f%%，累计%.2f%%，成交量%.1f倍(缩量蓄力)",
                            days, maxDailyChange, totalChange, vr))
                    .build());
        }
        return matched;
    }
}
