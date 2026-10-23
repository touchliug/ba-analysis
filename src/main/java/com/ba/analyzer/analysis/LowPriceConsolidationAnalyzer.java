package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
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
 * 低位插针 —— 低位横盘中出现长下影(下探后被买回), 视为支撑测试。
 *
 * 条件: 窗口内价格整体变化有限 + 现价处于较长回看期的低分位 + 至少一根有效插针。
 * 有效插针 = 下影 >= 实体×wickBodyRatio 且 下影 >= 当日振幅×minWickRangeRatio。
 * (原实现"任一天影线长于实体"7天内几乎必然满足, 形同虚设; 低位只看30天且历史不足即判低位。)
 */
@Slf4j
@Component
public class LowPriceConsolidationAnalyzer extends AbstractKlineAnalyzer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM-dd");

    public LowPriceConsolidationAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "低位插针";
    }

    @Override
    public String getDescription() {
        return buildDescription(getDefaultParams());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getLowPriceConsolidation().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var c = appProperties.getAnalysis().getLowPriceConsolidation();
        return createParams("days", c.getDays(),
                "maxPriceChangePercent", c.getMaxPriceChangePercent(),
                "lowPricePercentile", c.getLowPricePercentile(),
                "lowLookbackDays", c.getLowLookbackDays(),
                "wickBodyRatio", c.getWickBodyRatio(),
                "minWickRangeRatio", c.getMinWickRangeRatio(),
                "volumeSurgeRatio", c.getVolumeSurgeRatio());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var c = appProperties.getAnalysis().getLowPriceConsolidation();
        return createParams("days", getIntParam(requestParams, "days", c.getDays()),
                "maxPriceChangePercent", getDoubleParam(requestParams, "maxPriceChangePercent", c.getMaxPriceChangePercent()),
                "lowPricePercentile", getDoubleParam(requestParams, "lowPricePercentile", c.getLowPricePercentile()),
                "lowLookbackDays", getIntParam(requestParams, "lowLookbackDays", c.getLowLookbackDays()),
                "wickBodyRatio", getDoubleParam(requestParams, "wickBodyRatio", c.getWickBodyRatio()),
                "minWickRangeRatio", getDoubleParam(requestParams, "minWickRangeRatio", c.getMinWickRangeRatio()),
                "volumeSurgeRatio", getDoubleParam(requestParams, "volumeSurgeRatio", c.getVolumeSurgeRatio()));
    }

    @Override
    public Map<String, Object> getParamSpec() {
        var c = appProperties.getAnalysis().getLowPriceConsolidation();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("days", Analyzer.param("int", "横盘观察窗口(天)", c.getDays()));
        s.put("maxPriceChangePercent", Analyzer.param("double", "窗口内价格整体变化上限%(绝对值)", c.getMaxPriceChangePercent()));
        s.put("lowPricePercentile", Analyzer.param("double", "低位: 现价≤回看期收盘价的该分位数(0-100)", c.getLowPricePercentile()));
        s.put("lowLookbackDays", Analyzer.param("int", "低位回看天数", c.getLowLookbackDays()));
        s.put("wickBodyRatio", Analyzer.param("double", "插针: 下影≥实体×此倍数", c.getWickBodyRatio()));
        s.put("minWickRangeRatio", Analyzer.param("double", "插针: 下影≥当日振幅×此比例(0-1)", c.getMinWickRangeRatio()));
        s.put("volumeSurgeRatio", Analyzer.param("double", "插针日量比下限(1.0=不过滤)", c.getVolumeSurgeRatio()));
        return s;
    }

    @Override
    protected Map<String, List<KlineData>> fetchData(List<String> symbols, Map<String, Object> params) {
        int days = (int) params.get("days");
        int lookback = (int) params.get("lowLookbackDays");
        return dataFetchService.fetchDailyKlines(symbols, days + lookback + 1);
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("%d天内价格变化≤%.0f%%、处于%d天低位(≤%.0f%%分位)且出现长下影插针的币",
                (int) params.get("days"), (double) params.get("maxPriceChangePercent"),
                (int) params.get("lowLookbackDays"), (double) params.get("lowPricePercentile"));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        double maxPriceChange = (double) params.get("maxPriceChangePercent");
        double lowPercentile = (double) params.get("lowPricePercentile");
        int lookback = (int) params.get("lowLookbackDays");
        double wickBodyRatio = (double) params.get("wickBodyRatio");
        double minWickRangeRatio = (double) params.get("minWickRangeRatio");
        double volumeSurgeRatio = (double) params.get("volumeSurgeRatio");
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < days + 1) continue;

            List<KlineData> recent = klines.subList(klines.size() - days, klines.size());
            List<KlineData> history = klines.subList(Math.max(0, klines.size() - days - lookback), klines.size() - days);
            // 历史不足回看期一半: 低位判断不可靠, 跳过 (原实现直接判为低位)
            if (history.size() < lookback / 2) continue;

            double basePrice = history.get(history.size() - 1).getClosePrice();
            double lastPrice = recent.get(recent.size() - 1).getClosePrice();
            if (basePrice <= 0) continue;
            double priceChange = (lastPrice / basePrice - 1) * 100;
            if (Math.abs(priceChange) > maxPriceChange) continue;

            double threshold = percentile(history, lowPercentile);
            if (lastPrice > threshold) continue;

            double windowAvgVol = recent.stream().mapToDouble(KlineData::getQuoteVolume).average().orElse(0);
            int pinCount = 0;
            KlineData bestPin = null;
            double bestWickRatio = 0;
            double maxPinVolRatio = 0;
            for (KlineData k : recent) {
                double range = k.getHighPrice() - k.getLowPrice();
                if (range <= 0) continue;
                double body = Math.abs(k.getClosePrice() - k.getOpenPrice());
                double lowerWick = Math.min(k.getOpenPrice(), k.getClosePrice()) - k.getLowPrice();
                double wickRatio = lowerWick / range;
                if (lowerWick < body * wickBodyRatio || wickRatio < minWickRangeRatio) continue;
                pinCount++;
                if (windowAvgVol > 0) maxPinVolRatio = Math.max(maxPinVolRatio, k.getQuoteVolume() / windowAvgVol);
                if (wickRatio > bestWickRatio) {
                    bestWickRatio = wickRatio;
                    bestPin = k;
                }
            }
            if (bestPin == null) continue;
            if (volumeSurgeRatio > 1.0 && maxPinVolRatio < volumeSurgeRatio) continue;

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(lastPrice)
                    .changePercent(priceChange)
                    .detail(String.format("%d天价格%+.2f%% 插针%d次(最强%s 下影占振幅%.0f%% 低点%.6f 量比%.1fx) "
                                    + "现价≤%d天%.0f%%分位(%.6f)",
                            days, priceChange, pinCount,
                            Instant.ofEpochMilli(bestPin.getOpenTime()).atOffset(ZoneOffset.ofHours(8)).format(DATE_FMT),
                            bestWickRatio * 100, bestPin.getLowPrice(), maxPinVolRatio,
                            history.size(), lowPercentile, threshold))
                    .build());
        }
        return matched;
    }

    private static double percentile(List<KlineData> history, double pct) {
        List<Double> closes = history.stream().map(KlineData::getClosePrice).sorted().toList();
        int idx = (int) (closes.size() * pct / 100);
        return closes.get(Math.min(Math.max(idx, 0), closes.size() - 1));
    }
}
