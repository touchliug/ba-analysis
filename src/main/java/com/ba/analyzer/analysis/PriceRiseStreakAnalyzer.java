package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 价格连续上涨: 连续 N 根已收盘 K 线收阳 (close > open)。 */
@Slf4j
@Component
public class PriceRiseStreakAnalyzer extends AbstractStreakAnalyzer {

    public PriceRiseStreakAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "价格连续上涨";
    }

    @Override
    public String getDescription() {
        return buildDescription(getDefaultParams());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getPriceRiseStreak().isEnabled();
    }

    @Override
    protected String getConfiguredInterval() {
        return appProperties.getAnalysis().getPriceRiseStreak().getInterval();
    }

    @Override
    protected int getConfiguredPeriods() {
        return appProperties.getAnalysis().getPriceRiseStreak().getPeriods();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        return params("interval", configuredInterval(), "periods", normalizedConfiguredPeriods());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        return params(
                "interval", normalizeInterval(
                        requestParams == null ? null : requestParams.get("interval"),
                        configuredInterval()),
                "periods", normalizePeriods(
                        requestParams == null ? null : requestParams.get("periods"),
                        normalizedConfiguredPeriods()));
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("连续%d根%sK线收阳(close>open)的币",
                (int) params.get("periods"), intervalLabel((String) params.get("interval")));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            List<String> symbols, Map<String, Object> params) {
        String interval = (String) params.get("interval");
        int periods = (int) params.get("periods");
        Map<String, List<KlineData>> klineMap = closedCandles(
                dataFetchService.fetchKlines(symbols, interval, periods + EXTRA_PERIODS),
                interval, System.currentTimeMillis());

        List<AnalysisReport.CoinAnalysis> matched = newMatches();
        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            List<KlineData> klines = entry.getValue();
            if (klines.size() < periods) continue;

            int run = 0;
            for (int i = klines.size() - 1; i >= 0; i--) {
                if (!isBullish(klines.get(i))) break;
                run++;
            }
            if (run < periods) continue;

            KlineData first = klines.get(klines.size() - run);
            KlineData last = klines.get(klines.size() - 1);
            double change = first.getOpenPrice() > 0
                    ? (last.getClosePrice() / first.getOpenPrice() - 1) * 100 : 0;

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(entry.getKey())
                    .currentPrice(last.getClosePrice())
                    .changePercent(change)
                    .detail(String.format("%s连阳%s根 累计%+.2f%% 收盘%s | 截至%s",
                            interval, streakLabel(run, klines.size()), change,
                            plain(last.getClosePrice()), dateOf(last.getOpenTime(), interval)))
                    .build());
        }
        return matched;
    }
}
