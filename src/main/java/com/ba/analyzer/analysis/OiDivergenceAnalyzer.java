package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 持仓背离 —— 合并原"持仓量连增"与"价涨仓增"。
 *
 * 窗口内持仓量(币本位, 不受价格涨跌影响)明显增加, 而价格涨跌幅有限 = 有资金在建仓但价格还没反映。
 * 方向按价格判断: 价稳/升 → 偏多(多头建仓); 价跌 → 偏空(空头建仓)。
 * 资金费率作为拥挤度参考: 偏多且费率>0.05% 标 [多头拥挤], 偏空且费率<-0.05% 标 [空头拥挤]。
 *
 * 日线OI点时间戳为 UTC 00:00, 即前一日收盘时刻的快照, 与已收盘日K对齐: 取 days+1 个点覆盖 days 个区间。
 */
@Slf4j
@Component
public class OiDivergenceAnalyzer extends AbstractKlineAnalyzer {

    private static final double CROWDED_FUNDING = 0.0005;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM-dd");

    public OiDivergenceAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "持仓背离";
    }

    @Override
    public String getDescription() {
        return buildDescription(getDefaultParams());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getOiDivergence().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var c = appProperties.getAnalysis().getOiDivergence();
        return createParams("days", c.getDays(),
                "minOiRisePercent", c.getMinOiRisePercent(),
                "minOiUpDays", c.getMinOiUpDays(),
                "minPriceChangePercent", c.getMinPriceChangePercent(),
                "maxPriceChangePercent", c.getMaxPriceChangePercent());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var c = appProperties.getAnalysis().getOiDivergence();
        return createParams("days", getIntParam(requestParams, "days", c.getDays()),
                "minOiRisePercent", getDoubleParam(requestParams, "minOiRisePercent", c.getMinOiRisePercent()),
                "minOiUpDays", getIntParam(requestParams, "minOiUpDays", c.getMinOiUpDays()),
                "minPriceChangePercent", getDoubleParam(requestParams, "minPriceChangePercent", c.getMinPriceChangePercent()),
                "maxPriceChangePercent", getDoubleParam(requestParams, "maxPriceChangePercent", c.getMaxPriceChangePercent()));
    }

    @Override
    public Map<String, Object> getParamSpec() {
        var c = appProperties.getAnalysis().getOiDivergence();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("days", Analyzer.param("int", "统计天数", c.getDays()));
        s.put("minOiRisePercent", Analyzer.param("double", "持仓量总增幅下限%(币本位)", c.getMinOiRisePercent()));
        s.put("minOiUpDays", Analyzer.param("int", "持仓量上涨的最少天数", c.getMinOiUpDays()));
        s.put("minPriceChangePercent", Analyzer.param("double", "价格变化下限%(收盘对收盘)", c.getMinPriceChangePercent()));
        s.put("maxPriceChangePercent", Analyzer.param("double", "价格变化上限%", c.getMaxPriceChangePercent()));
        return s;
    }

    @Override
    protected Map<String, List<KlineData>> fetchData(List<String> symbols, Map<String, Object> params) {
        // 窗口 days 根 + 窗口前基准根 + 当前未收盘根
        return dataFetchService.fetchDailyKlines(symbols, (int) params.get("days") + 2);
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("%d天内持仓量增≥%.0f%%(≥%d天增), 价格变化%.0f%%~%.0f%%: 价稳/升偏多, 价跌偏空",
                (int) params.get("days"), (double) params.get("minOiRisePercent"), (int) params.get("minOiUpDays"),
                (double) params.get("minPriceChangePercent"), (double) params.get("maxPriceChangePercent"));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        double minOiRise = (double) params.get("minOiRisePercent");
        int minOiUpDays = (int) params.get("minOiUpDays");
        double minPriceChange = (double) params.get("minPriceChangePercent");
        double maxPriceChange = (double) params.get("maxPriceChangePercent");

        List<String> symbols = new ArrayList<>(klineMap.keySet());
        Map<String, List<OpenInterestData>> oiMap = dataFetchService.fetchOpenInterestHistoryBatch(symbols, days + 1);
        Map<String, Double> funding = dataFetchService.getLastFundingRates();
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < days + 1) continue;
            KlineData last = klines.get(klines.size() - 1);
            double basePrice = klines.get(klines.size() - 1 - days).getClosePrice();
            if (basePrice <= 0) continue;
            double priceChange = (last.getClosePrice() / basePrice - 1) * 100;
            if (priceChange < minPriceChange || priceChange > maxPriceChange) continue;

            List<OpenInterestData> oi = oiMap.get(symbol);
            if (oi == null || oi.size() < days + 1) continue;
            oi = oi.subList(oi.size() - days - 1, oi.size());
            // OI 与K线错位超过1.5天(某一侧同步滞后)则跳过
            long oiTs = oi.get(oi.size() - 1).getTimestamp();
            if (oiTs > 0 && last.getCloseTime() > 0 && Math.abs(oiTs - last.getCloseTime()) > DAY_MS * 3 / 2) continue;

            double oiFirst = oi.get(0).getOpenInterestValue();
            double oiLast = oi.get(oi.size() - 1).getOpenInterestValue();
            if (oiFirst <= 0) continue;
            double oiChange = (oiLast / oiFirst - 1) * 100;
            if (oiChange < minOiRise) continue;

            int upDays = 0;
            for (int i = 1; i < oi.size(); i++) {
                if (oi.get(i).getOpenInterestValue() > oi.get(i - 1).getOpenInterestValue()) upDays++;
            }
            if (upDays < minOiUpDays) continue;

            boolean bullish = priceChange >= 0;
            Double rate = funding.get(symbol);
            String crowded = "";
            if (rate != null) {
                if (bullish && rate > CROWDED_FUNDING) crowded = "[多头拥挤]";
                else if (!bullish && rate < -CROWDED_FUNDING) crowded = "[空头拥挤]";
            }

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(last.getClosePrice())
                    .changePercent(oiChange)
                    .side(bullish ? "long" : "short")
                    .detail(String.format("[%s]%s %d天持仓%+.1f%%(%d/%d天增) 价格%+.2f%% 费率%s | 截至%s收盘",
                            bullish ? "偏多" : "偏空", crowded, days, oiChange, upDays, days, priceChange,
                            rate == null ? "-" : String.format("%+.4f%%", rate * 100),
                            Instant.ofEpochMilli(last.getOpenTime()).atOffset(ZoneOffset.ofHours(8)).format(DATE_FMT)))
                    .build());
        }
        return matched;
    }
}
