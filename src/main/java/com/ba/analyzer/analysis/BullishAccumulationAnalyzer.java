package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 底部吸筹分析器 (数据驱动重构版)
 *
 * 基于53K样本回测, 用4个真正预测"7日内暴涨10%+"的因子:
 * - P(底部位置): 价格在N日低位 + 大幅回撤 = 跌够了
 * - H(低点抬高): 后半段最低点 > 前半段最低点 = 买方在抬价
 * - V(量能回升): 后半段成交量 > 前半段 = 资金在进场(不是缩量!)
 * - R(振幅收敛): 后半段振幅 < 前半段 = 变盘前夜
 */
@Slf4j
@Component
public class BullishAccumulationAnalyzer extends AbstractKlineAnalyzer {

    public BullishAccumulationAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "底部吸筹";
    }

    @Override
    public String getDescription() {
        int minScore = appProperties.getAnalysis().getBullishAccumulation().getMinScore();
        return String.format("底部位置+低点抬高+量能回升+振幅收敛, 吸筹评分≥%d的币", minScore);
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getBullishAccumulation().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getBullishAccumulation();
        return createParams("days", cfg.getDays(), "minScore", cfg.getMinScore(),
                "priceLookbackDays", cfg.getPriceLookbackDays(),
                "drawdownMin", cfg.getDrawdownMin(),
                "requireHigherLow", cfg.isRequireHigherLow(),
                "volumeIncreaseMin", cfg.getVolumeIncreaseMin(),
                "rangeContractionMax", cfg.getRangeContractionMax());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getBullishAccumulation();
        return createParams("days", getIntParam(requestParams, "days", cfg.getDays()),
                "minScore", getIntParam(requestParams, "minScore", cfg.getMinScore()),
                "priceLookbackDays", getIntParam(requestParams, "priceLookbackDays", cfg.getPriceLookbackDays()),
                "drawdownMin", getDoubleParam(requestParams, "drawdownMin", cfg.getDrawdownMin()),
                "requireHigherLow", getBoolParam(requestParams, "requireHigherLow", cfg.isRequireHigherLow()),
                "volumeIncreaseMin", getDoubleParam(requestParams, "volumeIncreaseMin", cfg.getVolumeIncreaseMin()),
                "rangeContractionMax", getDoubleParam(requestParams, "rangeContractionMax", cfg.getRangeContractionMax()));
    }

    @Override
    protected void sortResults(List<AnalysisReport.CoinAnalysis> matched) {
        matched.sort(Comparator.comparingInt((AnalysisReport.CoinAnalysis c) ->
                c.getScore() != null ? c.getScore() : 0).reversed());
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("底部位置+低点抬高+量能回升+振幅收敛, 吸筹评分≥%d的币",
                (int) params.get("minScore"));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        int minScore = (int) params.get("minScore");
        int priceLookbackDays = (int) params.get("priceLookbackDays");
        double drawdownMin = (double) params.get("drawdownMin");
        boolean requireHigherLow = (boolean) params.get("requireHigherLow");
        double volumeIncreaseMin = (double) params.get("volumeIncreaseMin");
        double rangeContractionMax = (double) params.get("rangeContractionMax");

        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            int minNeeded = days + priceLookbackDays;
            if (klines.size() < minNeeded) continue;

            List<KlineData> recent = klines.subList(klines.size() - days, klines.size());
            List<KlineData> lookback = klines.subList(klines.size() - days - priceLookbackDays, klines.size() - days);
            if (lookback.isEmpty()) continue;

            double startPrice = recent.get(0).getOpenPrice();
            double currentPrice = recent.get(recent.size() - 1).getClosePrice();
            double priceChange = startPrice > 0 ? (currentPrice - startPrice) / startPrice * 100 : 0;

            // ============================
            // P — 底部位置 (0-30)
            // ============================
            double lookbackHigh = lookback.stream().mapToDouble(KlineData::getHighPrice).max().orElse(0);
            double lookbackLow = lookback.stream().mapToDouble(KlineData::getLowPrice).min().orElse(0);
            double lookbackRange = lookbackHigh - lookbackLow;
            double pricePos = lookbackRange > 0 ? (currentPrice - lookbackLow) / lookbackRange : 0.5;
            double drawdown = lookbackHigh > 0 ? (lookbackHigh - currentPrice) / lookbackHigh * 100 : 0;

            int pScore;
            if (pricePos <= 0.15 && drawdown >= drawdownMin) pScore = 30;
            else if (pricePos <= 0.25 && drawdown >= drawdownMin * 0.7) pScore = 22;
            else if (pricePos <= 0.35) pScore = 15;
            else if (pricePos <= 0.50) pScore = 8;
            else pScore = 0;

            // ============================
            // H — 低点抬高 (0-25)
            // ============================
            int half = days / 2;
            List<KlineData> firstHalf = recent.subList(0, half);
            List<KlineData> secondHalf = recent.subList(half, days);
            double firstHalfLow = firstHalf.stream().mapToDouble(KlineData::getLowPrice).min().orElse(0);
            double secondHalfLow = secondHalf.stream().mapToDouble(KlineData::getLowPrice).min().orElse(0);
            double higherLowPct = firstHalfLow > 0 ? (secondHalfLow - firstHalfLow) / firstHalfLow * 100 : 0;

            int hScore;
            if (requireHigherLow && higherLowPct <= 0) {
                hScore = 0; // Hard requirement not met
            } else if (higherLowPct >= 5) hScore = 25;
            else if (higherLowPct >= 2) hScore = 18;
            else if (higherLowPct > 0) hScore = 10;
            else hScore = 0;

            // ============================
            // V — 量能回升 (0-25)
            // ============================
            double firstHalfAvgVol = firstHalf.stream().mapToDouble(KlineData::getQuoteVolume).average().orElse(0);
            double secondHalfAvgVol = secondHalf.stream().mapToDouble(KlineData::getQuoteVolume).average().orElse(0);
            double volIncrease = firstHalfAvgVol > 0 ? secondHalfAvgVol / firstHalfAvgVol : 0;

            int vScore;
            if (volIncrease >= 2.0) vScore = 25;
            else if (volIncrease >= volumeIncreaseMin) vScore = 20;
            else if (volIncrease >= 1.0) vScore = 10;
            else vScore = 0;

            // ============================
            // R — 振幅收敛 (0-20)
            // ============================
            double firstHalfAmp = calcAvgAmplitude(firstHalf);
            double secondHalfAmp = calcAvgAmplitude(secondHalf);
            double rangeRatio = firstHalfAmp > 0 ? secondHalfAmp / firstHalfAmp : 1.0;

            int rScore;
            if (rangeRatio <= 0.5) rScore = 20;
            else if (rangeRatio <= rangeContractionMax) rScore = 15;
            else if (rangeRatio <= 1.0) rScore = 8;
            else rScore = 0;

            // Count consolidation days: how many days price stayed within narrow range
            double recentAvgPrice = recent.stream().mapToDouble(KlineData::getClosePrice).average().orElse(0);
            int consolidationDays = 0;
            for (int d = recent.size() - 1; d >= 0; d--) {
                double dev = Math.abs((recent.get(d).getClosePrice() - recentAvgPrice) / recentAvgPrice * 100);
                if (dev < 3.0) consolidationDays++;
                else break;
            }

            int totalScore = pScore + hScore + vScore + rScore;
            if (totalScore < minScore) continue;

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(currentPrice)
                    .changePercent(priceChange)
                    .score(totalScore)
                    .detail(String.format(
                            "吸筹评分%d P%d H%d V%d R%d | 价格低位%.0f%% 回撤%.1f%% 低点%+.1f%% 量增%.1fx 振幅收敛%.1fx 横盘%d天",
                            totalScore, pScore, hScore, vScore, rScore,
                            pricePos * 100, drawdown, higherLowPct, volIncrease, rangeRatio, consolidationDays))
                    .build());
        }
        return matched;
    }

    private double calcAvgAmplitude(List<KlineData> klines) {
        double total = 0;
        int count = 0;
        for (KlineData k : klines) {
            double range = k.getHighPrice() - k.getLowPrice();
            double mid = (k.getHighPrice() + k.getLowPrice()) / 2;
            if (mid > 0) {
                total += range / mid * 100;
                count++;
            }
        }
        return count > 0 ? total / count : 0;
    }

    private boolean getBoolParam(Map<String, Object> params, String key, boolean defaultValue) {
        if (params == null || !params.containsKey(key)) return defaultValue;
        Object val = params.get(key);
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }
}
