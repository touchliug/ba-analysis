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
public class DropThenRiseAnalyzer extends AbstractKlineAnalyzer {

    public DropThenRiseAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "连跌今涨";
    }

    @Override
    public String getDescription() {
        int days = appProperties.getAnalysis().getDropThenRise().getDays();
        return String.format("最近%d天一直在跌但今天上涨的币", days);
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getDropThenRise().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getDropThenRise();
        return createParams("days", cfg.getDays(), "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getDropThenRise();
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
    protected String buildDescription(Map<String, Object> params) {
        return String.format("最近%d天一直在跌但今天上涨的币", (int) params.get("days"));
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

            boolean prevAllDrop = prevDays.stream().allMatch(k -> k.getChangePercent() < 0);
            boolean todayRise = today.getChangePercent() > 0;
            if (!prevAllDrop || !todayRise) continue;

            double prevAvgVolume = prevDays.stream().mapToDouble(KlineData::getQuoteVolume).average().orElse(1);
            double vr = prevAvgVolume > 0 ? today.getQuoteVolume() / prevAvgVolume : 1.0;
            if (volumeSurgeRatio > 1.0 && vr < volumeSurgeRatio) continue;

            double totalDrop = prevDays.stream().mapToDouble(KlineData::getChangePercent).sum();

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(today.getClosePrice())
                    .changePercent(today.getChangePercent())
                    .detail(String.format("前%d天累计跌%.2f%%，今日涨%.2f%%，今日反弹放量%.1f倍",
                            days, totalDrop, today.getChangePercent(), vr))
                    .build());
        }
        return matched;
    }
}
