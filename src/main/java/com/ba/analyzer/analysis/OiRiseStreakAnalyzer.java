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

/**
 * OI 连续上涨: 连续 N 个周期 sum_open_interest(币本位数量) 严格递增。
 * 同时读取同周期 K 线，仅用于在结果里展示当前价格。
 */
@Slf4j
@Component
public class OiRiseStreakAnalyzer extends AbstractStreakAnalyzer {

    public OiRiseStreakAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "OI连续上涨";
    }

    @Override
    public String getDescription() {
        return buildDescription(getDefaultParams());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getOiRiseStreak().isEnabled();
    }

    @Override
    protected String getConfiguredInterval() {
        return appProperties.getAnalysis().getOiRiseStreak().getInterval();
    }

    @Override
    protected int getConfiguredPeriods() {
        return appProperties.getAnalysis().getOiRiseStreak().getPeriods();
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
        return String.format("连续%d个%s周期OI(币本位sum_open_interest)递增的币",
                (int) params.get("periods"), intervalLabel((String) params.get("interval")));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            List<String> symbols, Map<String, Object> params) {
        String interval = (String) params.get("interval");
        int periods = (int) params.get("periods");
        long now = System.currentTimeMillis();
        long maxStale = maxOiStalenessMs(interval);

        Map<String, List<OpenInterestData>> oiMap =
                dataFetchService.fetchOpenInterestHistoryBatch(symbols, interval, periods + EXTRA_PERIODS);
        if (oiMap == null) oiMap = Map.of();
        Map<String, List<KlineData>> priceMap = dataFetchService.fetchKlines(symbols, interval, 2);
        if (priceMap == null) priceMap = Map.of();

        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();
        for (Map.Entry<String, List<OpenInterestData>> entry : oiMap.entrySet()) {
            List<OpenInterestData> oi = entry.getValue();
            if (oi == null || oi.size() < periods + 1) continue;

            OpenInterestData latest = oi.get(oi.size() - 1);
            if (latest.getTimestamp() <= 0 || now - latest.getTimestamp() > maxStale) continue;

            int run = 0;
            for (int i = oi.size() - 1; i > 0; i--) {
                if (oi.get(i).getOpenInterestValue() <= oi.get(i - 1).getOpenInterestValue()) break;
                run++;
            }
            if (run < periods) continue;

            OpenInterestData first = oi.get(oi.size() - 1 - run);
            double firstOi = first.getOpenInterestValue();
            double lastOi = latest.getOpenInterestValue();
            if (firstOi <= 0) continue;
            double oiChange = (lastOi / firstOi - 1) * 100;

            double price = 0;
            List<KlineData> klines = priceMap.get(entry.getKey());
            if (klines != null && !klines.isEmpty()) {
                price = klines.get(klines.size() - 1).getClosePrice();
            }

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(entry.getKey())
                    .currentPrice(price)
                    .changePercent(oiChange)
                    .detail(String.format("%s OI连涨%s期 累计%+.2f%% 最新OI%s(名义%.0f USDT) | 截至%s",
                            interval, streakLabel(run, oi.size()), oiChange,
                            plain(lastOi), latest.getOpenInterestNotionalValue(),
                            dateOf(latest.getTimestamp(), interval)))
                    .build());
        }
        return matched;
    }
}
