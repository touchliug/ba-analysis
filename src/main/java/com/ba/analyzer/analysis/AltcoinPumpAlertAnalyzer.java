package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 山寨币暴涨预警分析器 (数据驱动版)
 *
 * 基于SQLite历史数据回测(72个40%+暴涨事件)优化权重:
 * - 吃货比: 最强预测因子(r=+0.239), 60%暴涨前>=1.3
 * - 量能扩张: 第二强因子(r=+0.221), 最后一日放量是关键
 * - 起爆K线: 大实体+高收盘+放量, 区分临界爆发点
 * - OI蓄力: 数据覆盖有限, 降为加分项而非前置条件
 * - 缩量横盘: 仅20-30%暴涨符合此模式, 不做硬性要求
 * - 下影线: 弱相关(r=-0.044), 保留最低权重
 */
@Slf4j
@Component
public class AltcoinPumpAlertAnalyzer extends AbstractKlineAnalyzer {

    public AltcoinPumpAlertAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "山寨币暴涨预警";
    }

    @Override
    public String getDescription() {
        int minScore = appProperties.getAnalysis().getAltcoinPumpAlert().getMinScore();
        return String.format("数据驱动:吃货比+量能扩张+起爆检测+OI蓄力, 预警评分>=%d的币", minScore);
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getAltcoinPumpAlert().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getAltcoinPumpAlert();
        return createParams("days", cfg.getDays(), "minScore", cfg.getMinScore(),
                "maxPriceDeclinePercent", cfg.getMaxPriceDeclinePercent(),
                "oiConsecutiveDays", cfg.getOiConsecutiveDays(),
                "volumeExpansionRatio", cfg.getVolumeExpansionRatio(),
                "lowPricePercentile", cfg.getLowPricePercentile(),
                "wickBodyRatio", cfg.getWickBodyRatio(),
                "silentBuyerRatio", cfg.getSilentBuyerRatio(),
                "maxDrawdownPercent", cfg.getMaxDrawdownPercent());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getAltcoinPumpAlert();
        return createParams("days", getIntParam(requestParams, "days", cfg.getDays()),
                "minScore", getIntParam(requestParams, "minScore", cfg.getMinScore()),
                "maxPriceDeclinePercent", getDoubleParam(requestParams, "maxPriceDeclinePercent", cfg.getMaxPriceDeclinePercent()),
                "oiConsecutiveDays", getIntParam(requestParams, "oiConsecutiveDays", cfg.getOiConsecutiveDays()),
                "volumeExpansionRatio", getDoubleParam(requestParams, "volumeExpansionRatio", cfg.getVolumeExpansionRatio()),
                "lowPricePercentile", getDoubleParam(requestParams, "lowPricePercentile", cfg.getLowPricePercentile()),
                "wickBodyRatio", getDoubleParam(requestParams, "wickBodyRatio", cfg.getWickBodyRatio()),
                "silentBuyerRatio", getDoubleParam(requestParams, "silentBuyerRatio", cfg.getSilentBuyerRatio()),
                "maxDrawdownPercent", getDoubleParam(requestParams, "maxDrawdownPercent", cfg.getMaxDrawdownPercent()));
    }

    @Override
    protected void sortResults(List<AnalysisReport.CoinAnalysis> matched) {
        matched.sort(Comparator.comparingInt((AnalysisReport.CoinAnalysis c) ->
                c.getScore() != null ? c.getScore() : 0).reversed());
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("数据驱动:吃货比+量能扩张+起爆检测+OI蓄力, 预警评分>=%d的币",
                (int) params.get("minScore"));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        List<String> symbols = new ArrayList<>(klineMap.keySet());
        Map<String, List<OpenInterestData>> oiHistoryMap =
                dataFetchService.fetchOpenInterestHistoryBatch(symbols, days + 1);
        return doAnalyzeWithOi(klineMap, oiHistoryMap, params);
    }

    private List<AnalysisReport.CoinAnalysis> doAnalyzeWithOi(
            Map<String, List<KlineData>> klineMap,
            Map<String, List<OpenInterestData>> oiHistoryMap,
            Map<String, Object> params) {
        int days = (int) params.get("days");
        int minScore = (int) params.get("minScore");
        double maxPriceDeclinePercent = (double) params.get("maxPriceDeclinePercent");
        int oiConsecutiveDays = (int) params.get("oiConsecutiveDays");
        double volumeExpansionRatio = (double) params.get("volumeExpansionRatio");
        double lowPricePercentile = (double) params.get("lowPricePercentile");
        double wickBodyRatio = (double) params.get("wickBodyRatio");
        double silentBuyerRatioThreshold = (double) params.get("silentBuyerRatio");
        double maxDrawdownPercent = (double) params.get("maxDrawdownPercent");

        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < days) continue;

            List<KlineData> recentKlines = klines.subList(klines.size() - days, klines.size());
            double startPrice = recentKlines.get(0).getOpenPrice();
            double endPrice = recentKlines.get(recentKlines.size() - 1).getClosePrice();
            if (startPrice <= 0) continue;

            // === Gate 1: Price decline filter (only limit downside, trend continuation is valid) ===
            double priceChange = ((endPrice - startPrice) / startPrice) * 100;
            if (priceChange < -maxPriceDeclinePercent) continue;

            // === Gate 2: Drawdown filter (not in free-fall) ===
            double windowHigh = recentKlines.stream().mapToDouble(KlineData::getHighPrice).max().orElse(startPrice);
            double drawdown = windowHigh > 0 ? ((windowHigh - endPrice) / windowHigh) * 100 : 0;
            if (drawdown > maxDrawdownPercent) continue;

            double maxHigh = windowHigh;
            double minLow = recentKlines.stream().mapToDouble(KlineData::getLowPrice).min().orElse(0);
            List<KlineData> historicalKlines = klines.subList(0, klines.size() - days);

            // Pre-compute commonly used values
            KlineData latest = recentKlines.get(recentKlines.size() - 1);
            double recentAvgVol = recentKlines.stream().mapToDouble(KlineData::getQuoteVolume).average().orElse(0);
            double volExpansion = recentAvgVol > 0 ? latest.getQuoteVolume() / recentAvgVol : 1.0;
            double silentRatio = calcSilentBuyerRatio(recentKlines);

            // ========================
            // S — 吃货比 (0-30): 最强预测因子 r=+0.239
            // ========================
            int sScore;
            if (silentRatio > 2.5) sScore = 30;
            else if (silentRatio >= 2.0) sScore = 25;
            else if (silentRatio >= silentBuyerRatioThreshold) sScore = 20;
            else if (silentRatio >= 1.0) sScore = 10;
            else sScore = 0;

            // ========================
            // V — 量能扩张 (0-25): 第二强因子 r=+0.221
            // ========================
            int vScore;
            if (volExpansion >= 3.0) vScore = 25;
            else if (volExpansion >= 2.0) vScore = 20;
            else if (volExpansion >= volumeExpansionRatio) vScore = 15;
            else if (volExpansion >= 1.2) vScore = 8;
            else vScore = 0;

            // ========================
            // B — 起爆K线 (0-20): 大实体+高收盘+放量 = 临界爆发
            // ========================
            double latestRange = latest.getHighPrice() - latest.getLowPrice();
            double latestBody = Math.abs(latest.getClosePrice() - latest.getOpenPrice());
            double latestClosePos = latestRange > 0
                    ? (latest.getClosePrice() - latest.getLowPrice()) / latestRange : 0;
            double bodyRatio = latestRange > 0 ? latestBody / latestRange : 0;

            int bScore;
            if (bodyRatio >= 0.5 && latestClosePos >= 0.7 && volExpansion >= 2.0) bScore = 20;
            else if (bodyRatio >= 0.5 && latestClosePos >= 0.6) bScore = 12;
            else if (bodyRatio >= 0.4) bScore = 6;
            else bScore = 0;

            // ========================
            // O — OI蓄力 (0-15): 加分项, 数据覆盖有限
            // ========================
            int oScore = calcOiScore(oiHistoryMap.get(symbol), oiConsecutiveDays, priceChange);

            // ========================
            // P — 价格位置 (0-10): 低位有更多上涨空间
            // ========================
            int pScore = calcPositionScore(historicalKlines, endPrice, lowPricePercentile);

            // ========================
            // W — 下影承接 (0-5): 弱相关, 保留最低权重
            // ========================
            int wickCount = countWicks(recentKlines, wickBodyRatio);
            int wScore = wickCount >= 3 ? 5 : wickCount >= 1 ? 3 : 0;

            int totalScore = sScore + vScore + bScore + oScore + pScore + wScore;
            if (totalScore < minScore) continue;

            // Collect detail data
            double oiChange = 0;
            int oiConsecutive = 0;
            List<OpenInterestData> oiHistory = oiHistoryMap.get(symbol);
            if (oiHistory != null && oiHistory.size() >= 2) {
                double firstOi = oiHistory.get(0).getOpenInterestValue();
                double lastOi = oiHistory.get(oiHistory.size() - 1).getOpenInterestValue();
                if (firstOi > 0) oiChange = ((lastOi - firstOi) / firstOi) * 100;
                oiConsecutive = countOiConsecutiveRise(oiHistory);
            }

            String pattern = priceChange > 5 ? "趋势延续" : priceChange < -3 ? "超跌反弹" : "蓄力突破";

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(endPrice)
                    .changePercent(priceChange)
                    .score(totalScore)
                    .detail(String.format(
                            "预警%d S%d V%d B%d O%d P%d W%d | %s 吃货%.2f 放量%.1fx 起爆[实体%.0f%% 收盘%.0f%%] OI%+.1f%%连增%d天 低%.1f%% 针%d 回撤%.1f%%",
                            totalScore, sScore, vScore, bScore, oScore, pScore, wScore,
                            pattern, silentRatio, volExpansion,
                            bodyRatio * 100, latestClosePos * 100,
                            oiChange, oiConsecutive, priceChange, wickCount, drawdown))
                    .build());
        }
        return matched;
    }

    // ========================
    // OI scoring (0-15): bonus, not a gate
    // ========================
    private int calcOiScore(List<OpenInterestData> oiHistory, int oiConsecutiveDays, double priceChange) {
        if (oiHistory == null || oiHistory.size() < 2) return 0;
        double firstOi = oiHistory.get(0).getOpenInterestValue();
        double lastOi = oiHistory.get(oiHistory.size() - 1).getOpenInterestValue();
        if (firstOi <= 0) return 0;

        double oiTotalChange = ((lastOi - firstOi) / firstOi) * 100;
        int oiConsecutiveCount = countOiConsecutiveRise(oiHistory);

        int score = 0;
        if (oiConsecutiveCount >= oiConsecutiveDays && oiTotalChange >= 10) score = 15;
        else if (oiConsecutiveCount >= 2 && oiTotalChange >= 5) score = 10;
        else if (oiTotalChange >= 3) score = 5;
        return score;
    }

    private int countOiConsecutiveRise(List<OpenInterestData> oiHistory) {
        int count = 0;
        for (int i = oiHistory.size() - 1; i >= 1; i--) {
            if (oiHistory.get(i).getOpenInterestValue() > oiHistory.get(i - 1).getOpenInterestValue()) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    // ========================
    // Position score (0-10): reduced weight
    // ========================
    private int calcPositionScore(List<KlineData> historicalKlines, double currentPrice, double lowPricePercentile) {
        if (historicalKlines.isEmpty()) return 5;
        List<Double> historicalCloses = historicalKlines.stream()
                .map(KlineData::getClosePrice).sorted().toList();
        int index = (int) (historicalCloses.size() * lowPricePercentile / 100);
        double threshold = historicalCloses.get(Math.min(index, historicalCloses.size() - 1));
        if (threshold <= 0) return 5;

        double ratio = currentPrice / threshold;
        if (ratio <= 1.0) return 10;
        if (ratio <= 1.10) return 7;
        if (ratio <= 1.25) return 4;
        return 0;
    }

    // ========================
    // Wick count
    // ========================
    private int countWicks(List<KlineData> klines, double wickBodyRatio) {
        int count = 0;
        for (KlineData k : klines) {
            double lowerWick = Math.min(k.getOpenPrice(), k.getClosePrice()) - k.getLowPrice();
            double body = Math.abs(k.getClosePrice() - k.getOpenPrice());
            if (body > 0 && lowerWick > body * wickBodyRatio) count++;
        }
        return count;
    }

    // ========================
    // Silent buyer ratio
    // ========================
    private double calcSilentBuyerRatio(List<KlineData> klines) {
        double upVol = 0, downVol = 0;
        for (KlineData k : klines) {
            if (k.getClosePrice() > k.getOpenPrice()) upVol += k.getQuoteVolume();
            else downVol += k.getQuoteVolume();
        }
        if (downVol > 0) return upVol / downVol;
        return upVol > 0 ? 10.0 : 0;
    }
}
