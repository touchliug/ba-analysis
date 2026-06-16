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

@Slf4j
@Component
public class ShortTermRiseAnalyzer extends AbstractKlineAnalyzer {

    private record RecentStats(double maxVol, double avgVol, double maxPrice,
                               long upBarCount, double takerBuyRatio) {}

    public ShortTermRiseAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "短期急涨";
    }

    @Override
    public String getDescription() {
        var cfg = appProperties.getAnalysis().getShortTermRise();
        String timeRange = getTimeRangeDescription(cfg.getInterval(), cfg.getPeriod());
        return String.format("%s内上涨超过%.0f%%且进场质量≥%d分的币",
                timeRange, cfg.getThresholdPercent(), cfg.getMinScore());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getShortTermRise().isEnabled();
    }

    @Override
    public boolean requiresIntradayData() {
        return true;
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getShortTermRise();
        return createParams("interval", cfg.getInterval(), "period", cfg.getPeriod(),
                "thresholdPercent", cfg.getThresholdPercent(),
                "volumeSurgeRatio", cfg.getVolumeSurgeRatio(),
                "minScore", cfg.getMinScore());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getShortTermRise();
        String interval = getStringParam(requestParams, "interval", cfg.getInterval());
        int period = getIntParam(requestParams, "period", cfg.getPeriod());
        double threshold = getDoubleParam(requestParams, "thresholdPercent", cfg.getThresholdPercent());
        double vr = getDoubleParam(requestParams, "volumeSurgeRatio", cfg.getVolumeSurgeRatio());
        int minScore = getIntParam(requestParams, "minScore", cfg.getMinScore());
        return createParams("interval", interval, "period", period,
                "thresholdPercent", threshold, "volumeSurgeRatio", vr, "minScore", minScore);
    }

    @Override
    protected Map<String, List<KlineData>> fetchData(List<String> symbols, Map<String, Object> params) {
        String interval = (String) params.get("interval");
        int period = (int) params.get("period");
        return dataFetchService.fetchKlinesByInterval(symbols, interval, period + getHistoryBufferDays());
    }

    @Override
    protected void sortResults(List<AnalysisReport.CoinAnalysis> matched) {
        matched.sort(Comparator.comparingInt((AnalysisReport.CoinAnalysis c) ->
                c.getScore() != null ? c.getScore() : 0).reversed());
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        String interval = (String) params.get("interval");
        int period = (int) params.get("period");
        double threshold = (double) params.get("thresholdPercent");
        int minScore = (int) params.get("minScore");
        String timeRange = getTimeRangeDescription(interval, period);
        return String.format("%s内上涨超过%.0f%%且进场评分≥%d的币", timeRange, threshold, minScore);
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int period = (int) params.get("period");
        double threshold = (double) params.get("thresholdPercent");
        int minScore = (int) params.get("minScore");
        String interval = (String) params.get("interval");
        String timeRange = getTimeRangeDescription(interval, period);
        var cfg = appProperties.getAnalysis().getShortTermRise();
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < 2) continue;

            List<KlineData> recentKlines = klines.size() > period
                    ? klines.subList(klines.size() - period, klines.size()) : klines;
            double startPrice = recentKlines.get(0).getOpenPrice();
            double endPrice = recentKlines.get(recentKlines.size() - 1).getClosePrice();
            if (startPrice <= 0) continue;

            double changePercent = ((endPrice - startPrice) / startPrice) * 100;
            if (changePercent < threshold) continue;

            List<KlineData> historyForVol = klines.size() > period
                    ? klines.subList(0, klines.size() - period) : List.of();
            double histAvgVol = historyForVol.stream()
                    .mapToDouble(KlineData::getQuoteVolume).average().orElse(1);

            RecentStats stats = computeRecentStats(recentKlines);
            double peakVr = histAvgVol > 0 ? stats.maxVol / histAvgVol : 1.0;
            double avgVr = histAvgVol > 0 ? stats.avgVol / histAvgVol : 1.0;

            int[] scores = calculateScores(recentKlines, startPrice, endPrice, stats, histAvgVol, cfg);
            int totalScore = scores[0] + scores[1] + scores[2] + scores[3] + scores[4] + scores[5] + scores[6];
            if (totalScore < minScore) continue;

            String launchMark = scores[6] > 0 ? " | 起爆信号" : "";
            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(endPrice)
                    .changePercent(changePercent)
                    .score(totalScore)
                    .detail(String.format(
                            "%s内从%.4f涨到%.4f，涨幅%.2f%% | 评分%d P%d V%d B%d D%d C%d T%d%s | 峰值量比%.1f/均量比%.1f 主动比%.2f",
                            timeRange, startPrice, endPrice, changePercent,
                            totalScore, scores[0], scores[1], scores[2], scores[3], scores[4], scores[5],
                            launchMark, peakVr, avgVr, stats.takerBuyRatio))
                    .build());
        }
        return matched;
    }

    private RecentStats computeRecentStats(List<KlineData> recentKlines) {
        double maxVol = 0, sumVol = 0, maxPrice = 0;
        long upBars = 0;
        double totalTakerBuy = 0, totalQuoteVol = 0;
        for (KlineData k : recentKlines) {
            double vol = k.getQuoteVolume();
            sumVol += vol;
            if (vol > maxVol) maxVol = vol;
            double high = k.getHighPrice();
            if (high > maxPrice) maxPrice = high;
            if (k.getClosePrice() > k.getOpenPrice()) upBars++;
            totalTakerBuy += parseTakerDouble(k.getTakerBuyQuoteAssetVolume());
            totalQuoteVol += parseTakerDouble(k.getQuoteAssetVolume());
        }
        double avgVol = recentKlines.isEmpty() ? 0 : sumVol / recentKlines.size();
        double takerBuyRatio = totalQuoteVol > 0 ? totalTakerBuy / totalQuoteVol : 0;
        return new RecentStats(maxVol, avgVol, maxPrice, upBars, takerBuyRatio);
    }

    private double parseTakerDouble(String val) {
        if (val == null) return 0;
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { return 0; }
    }

    private int[] calculateScores(List<KlineData> recentKlines, double startPrice,
                                   double endPrice, RecentStats stats,
                                   double histAvgVol, AppProperties.ShortTermRiseConfig cfg) {
        int half = Math.max(1, recentKlines.size() / 2);
        List<KlineData> firstHalf = recentKlines.subList(0, half);
        List<KlineData> secondHalf = recentKlines.subList(half, recentKlines.size());
        double firstHalfRise = ((firstHalf.get(firstHalf.size() - 1).getClosePrice()
                - firstHalf.get(0).getOpenPrice()) / firstHalf.get(0).getOpenPrice()) * 100;
        double secondHalfRise = ((secondHalf.get(secondHalf.size() - 1).getClosePrice()
                - secondHalf.get(0).getOpenPrice()) / secondHalf.get(0).getOpenPrice()) * 100;
        double totalRise = Math.max(0, firstHalfRise) + Math.max(0, secondHalfRise);
        double momentumRatio = totalRise > 0 ? Math.max(0, secondHalfRise) / totalRise : 0;

        int pScore = momentumRatio > 0.7 ? 25 : momentumRatio > 0.5 ? 20 : momentumRatio > 0.3 ? 12 : 0;

        double peakVr = histAvgVol > 0 ? stats.maxVol / histAvgVol : 1.0;
        int vScore = peakVr >= 5.0 ? 25 : peakVr >= 3.0 ? 20 : peakVr >= 1.5 ? 12 : 0;

        KlineData lastBar = recentKlines.get(recentKlines.size() - 1);
        double barRange = lastBar.getHighPrice() - lastBar.getLowPrice();
        double bodyRatio = barRange > 0 ? Math.abs(lastBar.getClosePrice() - lastBar.getOpenPrice()) / barRange : 0;
        double closePosition = barRange > 0 ? (lastBar.getClosePrice() - lastBar.getLowPrice()) / barRange : 0;
        boolean bullish = lastBar.getClosePrice() > lastBar.getOpenPrice();

        int bScore;
        if (bodyRatio >= 0.6 && closePosition >= 0.8 && bullish) bScore = 25;
        else if (bodyRatio >= 0.5 && closePosition >= 0.6 && bullish) bScore = 18;
        else if (bodyRatio >= 0.3 && closePosition >= 0.5) bScore = 10;
        else bScore = 0;

        double overallRiseAmount = endPrice - startPrice;
        double drawdownRatio = overallRiseAmount > 0 ? (stats.maxPrice - endPrice) / overallRiseAmount : 1.0;
        int dScore = drawdownRatio < 0.15 ? 25 : drawdownRatio < 0.30 ? 18 : drawdownRatio < 0.50 ? 10 : 0;

        double upRatio = (double) stats.upBarCount / recentKlines.size();
        int cScore = upRatio >= 1.0 ? 25 : upRatio >= 0.8 ? 20 : upRatio >= 0.6 ? 12 : 0;

        int tScore = stats.takerBuyRatio >= 0.65 ? 25 : stats.takerBuyRatio >= 0.55 ? 18 : stats.takerBuyRatio >= 0.50 ? 10 : 0;

        int launchBonus = scanLaunchSignals(recentKlines, histAvgVol, cfg);

        return new int[]{pScore, vScore, bScore, dScore, cScore, tScore, launchBonus};
    }

    private int scanLaunchSignals(List<KlineData> recentKlines, double histAvgVol,
                                   AppProperties.ShortTermRiseConfig cfg) {
        for (KlineData k : recentKlines) {
            if (k.getClosePrice() <= k.getOpenPrice()) continue;
            double barRange = k.getHighPrice() - k.getLowPrice();
            if (barRange <= 0) continue;
            double bodyRatio = (k.getClosePrice() - k.getOpenPrice()) / barRange;
            double closePos = (k.getClosePrice() - k.getLowPrice()) / barRange;
            double singleVolRatio = histAvgVol > 0 ? k.getQuoteVolume() / histAvgVol : 0;
            if (bodyRatio >= cfg.getLaunchBodyRatio()
                    && closePos >= cfg.getLaunchClosePos()
                    && singleVolRatio >= cfg.getLaunchVolRatio()) {
                return 20;
            }
        }
        return 0;
    }

    private String getTimeRangeDescription(String interval, int period) {
        return switch (interval) {
            case "1m" -> period + "分钟";
            case "3m" -> (period * 3) + "分钟";
            case "5m" -> (period * 5) + "分钟";
            case "15m" -> (period * 15) + "分钟";
            case "30m" -> (period * 30) + "分钟";
            case "1h" -> period + "小时";
            case "2h" -> (period * 2) + "小时";
            case "4h" -> (period * 4) + "小时";
            case "6h" -> (period * 6) + "小时";
            case "8h" -> (period * 8) + "小时";
            case "12h" -> (period * 12) + "小时";
            default -> period + "个" + interval;
        };
    }

    private String getStringParam(Map<String, Object> params, String key, String defaultValue) {
        if (params == null || !params.containsKey(key)) return defaultValue;
        Object val = params.get(key);
        return val != null ? val.toString() : defaultValue;
    }
}
