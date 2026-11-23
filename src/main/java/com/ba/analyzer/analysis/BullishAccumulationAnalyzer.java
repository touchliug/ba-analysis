package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 底部吸筹分析器 (双轨蓄力观察池重构版)
 *
 * 目标: 在暴涨启动前(如 EVAA、BLESS 在 2026-11-13 还在坑里极度缩量、尚未大涨阶段),
 * 把正在蓄力的币捞进观察池盯盘, 并给出明确的「盯盘点火线」。
 *
 * 双轨并联打分 (满分 100, 基础分 30, 取最高轨得分):
 * - 轨道 1 [二波缩量坑]: 前 N 天有过放量主升(证明有庄) -> 回调 25%~55% 进坑(高点已过>=5天) -> 近 5 日缩量至峰值 15% 以内 -> 坑底止跌企稳
 * - 轨道 2 [首波OI潜伏] / [首波试盘潜伏]: 处于 N 天低分位且近 10 日窄幅横盘 -> 5 日币本位 OI 逆势净增>=15%(>=3天增) 或 前 14 日出现放量试盘脚印且近 3 日缩量回落
 */
@Slf4j
@Component
public class BullishAccumulationAnalyzer extends AbstractKlineAnalyzer {

    private static final int OI_POINTS = 6; // 覆盖 5 个日变动区间

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
        return String.format("双轨蓄力观察池(二波缩量坑 + 首波OI/试盘潜伏, 附盯盘点火线), 评分≥%d", minScore);
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getBullishAccumulation().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getBullishAccumulation();
        return createParams(
                "lookbackDays", cfg.getLookbackDays(),
                "minScore", cfg.getMinScore(),
                "minWaveRisePercent", cfg.getMinWaveRisePercent(),
                "waveDrawdownMin", cfg.getWaveDrawdownMin(),
                "waveDrawdownMax", cfg.getWaveDrawdownMax(),
                "maxDryVolumeRatio", cfg.getMaxDryVolumeRatio(),
                "lowPricePercentile", cfg.getLowPricePercentile(),
                "minOiRisePercent", cfg.getMinOiRisePercent(),
                "maxDailyRisePercent", cfg.getMaxDailyRisePercent(),
                "minLiquidityUsdt", cfg.getMinLiquidityUsdt());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getBullishAccumulation();
        return createParams(
                "lookbackDays", getIntParam(requestParams, "lookbackDays", cfg.getLookbackDays()),
                "minScore", getIntParam(requestParams, "minScore", cfg.getMinScore()),
                "minWaveRisePercent", getDoubleParam(requestParams, "minWaveRisePercent", cfg.getMinWaveRisePercent()),
                "waveDrawdownMin", getDoubleParam(requestParams, "waveDrawdownMin", cfg.getWaveDrawdownMin()),
                "waveDrawdownMax", getDoubleParam(requestParams, "waveDrawdownMax", cfg.getWaveDrawdownMax()),
                "maxDryVolumeRatio", getDoubleParam(requestParams, "maxDryVolumeRatio", cfg.getMaxDryVolumeRatio()),
                "lowPricePercentile", getDoubleParam(requestParams, "lowPricePercentile", cfg.getLowPricePercentile()),
                "minOiRisePercent", getDoubleParam(requestParams, "minOiRisePercent", cfg.getMinOiRisePercent()),
                "maxDailyRisePercent", getDoubleParam(requestParams, "maxDailyRisePercent", cfg.getMaxDailyRisePercent()),
                "minLiquidityUsdt", getDoubleParam(requestParams, "minLiquidityUsdt", cfg.getMinLiquidityUsdt()));
    }

    @Override
    public Map<String, Object> getParamSpec() {
        var c = appProperties.getAnalysis().getBullishAccumulation();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("lookbackDays", Analyzer.param("int", "结构与低位回看天数", c.getLookbackDays()));
        s.put("minScore", Analyzer.param("int", "最低蓄力评分", c.getMinScore()));
        s.put("minWaveRisePercent", Analyzer.param("double", "二波: 前波5日最小涨幅%", c.getMinWaveRisePercent()));
        s.put("waveDrawdownMin", Analyzer.param("double", "二波: 距前高最小回撤%", c.getWaveDrawdownMin()));
        s.put("waveDrawdownMax", Analyzer.param("double", "二波: 距前高最大回撤%", c.getWaveDrawdownMax()));
        s.put("maxDryVolumeRatio", Analyzer.param("double", "二波: 近5日均量/峰值5日均量上限", c.getMaxDryVolumeRatio()));
        s.put("lowPricePercentile", Analyzer.param("double", "首波: 低位分位数上限%(0-100)", c.getLowPricePercentile()));
        s.put("minOiRisePercent", Analyzer.param("double", "首波: 5日币本位OI增幅下限%", c.getMinOiRisePercent()));
        s.put("maxDailyRisePercent", Analyzer.param("double", "当日涨幅上限%(过滤已启动)", c.getMaxDailyRisePercent()));
        s.put("minLiquidityUsdt", Analyzer.param("double", "20日均成交额下限(USDT)", c.getMinLiquidityUsdt()));
        return s;
    }

    @Override
    protected Map<String, List<KlineData>> fetchData(List<String> symbols, Map<String, Object> params) {
        int lookbackDays = (int) params.get("lookbackDays");
        return dataFetchService.fetchDailyKlines(symbols, lookbackDays + 2);
    }

    @Override
    protected void sortResults(List<AnalysisReport.CoinAnalysis> matched) {
        matched.sort(Comparator.comparingInt((AnalysisReport.CoinAnalysis c) ->
                c.getScore() != null ? c.getScore() : 0)
                .thenComparingDouble(AnalysisReport.CoinAnalysis::getChangePercent)
                .reversed());
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("双轨蓄力观察池(二波缩量坑 + 首波OI/试盘潜伏, 附盯盘点火线), 评分≥%d",
                (int) params.get("minScore"));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int lookbackDays = Math.max(20, (int) params.get("lookbackDays"));
        int minScore = (int) params.get("minScore");
        double minWaveRisePercent = (double) params.get("minWaveRisePercent");
        double waveDrawdownMin = (double) params.get("waveDrawdownMin");
        double waveDrawdownMax = (double) params.get("waveDrawdownMax");
        double maxDryVolumeRatio = (double) params.get("maxDryVolumeRatio");
        double lowPricePercentile = (double) params.get("lowPricePercentile");
        double minOiRisePercent = (double) params.get("minOiRisePercent");
        double maxDailyRisePercent = (double) params.get("maxDailyRisePercent");
        double minLiquidityUsdt = (double) params.get("minLiquidityUsdt");

        List<String> symbols = new ArrayList<>(klineMap.keySet());
        Map<String, List<OpenInterestData>> oiMap =
                dataFetchService.fetchOpenInterestHistoryBatch(symbols, OI_POINTS);
        if (oiMap == null) oiMap = Collections.emptyMap();

        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines == null || klines.size() < lookbackDays) continue;

            List<KlineData> window = klines.subList(klines.size() - lookbackDays, klines.size());
            int e = window.size() - 1;
            KlineData latest = window.get(e);
            double currentPrice = latest.getClosePrice();
            double openPrice = latest.getOpenPrice();
            if (currentPrice <= 0 || openPrice <= 0) continue;

            // === Gate 2: 20日均成交额流动性门槛 ===
            int liqWindow = Math.min(20, window.size());
            double avgVol20 = 0;
            for (int i = window.size() - liqWindow; i <= e; i++) {
                avgVol20 += window.get(i).getQuoteVolume();
            }
            avgVol20 /= liqWindow;
            if (minLiquidityUsdt > 0 && avgVol20 < minLiquidityUsdt) continue;

            // === Gate 3: 还在坑里(未加速破位, 也未当日暴涨启动) ===
            double todayChg = (currentPrice / openPrice - 1) * 100;
            if (todayChg < -5.0 || todayChg >= maxDailyRisePercent) continue;

            double close3dAgo = window.get(e - 3).getClosePrice();
            double chg3d = close3dAgo > 0 ? (currentPrice / close3dAgo - 1) * 100 : 0;
            if (chg3d < -6.0 || chg3d > 10.0) continue;

            // === 5日币本位OI净增与递增天数 ===
            double oiRisePct = 0;
            int oiUpDays = 0;
            List<OpenInterestData> ois = oiMap.get(symbol);
            if (ois != null && ois.size() >= 2) {
                int startIdx = Math.max(0, ois.size() - OI_POINTS);
                double firstOi = ois.get(startIdx).getOpenInterestValue();
                double lastOi = ois.get(ois.size() - 1).getOpenInterestValue();
                if (firstOi > 0) {
                    oiRisePct = (lastOi / firstOi - 1) * 100;
                }
                for (int i = startIdx + 1; i < ois.size(); i++) {
                    if (ois.get(i).getOpenInterestValue() > ois.get(i - 1).getOpenInterestValue()) {
                        oiUpDays++;
                    }
                }
            }

            // === 轨道 1: [二波缩量坑] ===
            double avgVol60 = 0;
            for (KlineData k : window) avgVol60 += k.getQuoteVolume();
            avgVol60 /= window.size();

            double legRet = 0;
            double legVolMult = 0;
            double peak5Vol = 0;
            double swingHigh = 0;
            int swingHighIdx = 0;
            for (int i = 0; i <= e; i++) {
                double hi = window.get(i).getHighPrice();
                if (hi > swingHigh) {
                    swingHigh = hi;
                    swingHighIdx = i;
                }
                if (i >= 4) {
                    double sum5Vol = 0;
                    for (int j = i - 4; j <= i; j++) sum5Vol += window.get(j).getQuoteVolume();
                    double avg5Vol = sum5Vol / 5.0;
                    peak5Vol = Math.max(peak5Vol, avg5Vol);

                    double baseClose = window.get(i - 4).getClosePrice();
                    double r5 = baseClose > 0 ? (window.get(i).getClosePrice() / baseClose - 1) * 100 : 0;
                    if (r5 > legRet) {
                        legRet = r5;
                        legVolMult = avgVol60 > 0 ? avg5Vol / avgVol60 : 0;
                    }
                }
            }

            double drawdown = swingHigh > 0 ? (swingHigh - currentPrice) / swingHigh * 100 : 0;
            double dry5Vol = 0;
            double maxClose5 = 0;
            for (int i = e - 4; i <= e; i++) {
                dry5Vol += window.get(i).getQuoteVolume();
                maxClose5 = Math.max(maxClose5, window.get(i).getClosePrice());
            }
            dry5Vol /= 5.0;
            double dryRatio = peak5Vol > 0 ? dry5Vol / peak5Vol : 1.0;

            boolean prevBull = window.get(e - 1).getClosePrice() > window.get(e - 1).getOpenPrice();
            boolean todayBull = currentPrice > openPrice;

            int t1Score = 0;
            if (legRet >= minWaveRisePercent
                    && legVolMult >= 2.5
                    && drawdown >= waveDrawdownMin
                    && drawdown <= waveDrawdownMax
                    && (e - swingHighIdx) >= 5
                    && dryRatio <= maxDryVolumeRatio) {
                t1Score = 30;
                t1Score += dryRatio <= 0.05 ? 30 : dryRatio <= 0.10 ? 22 : 12;
                t1Score += legRet >= 100.0 ? 20 : legRet >= 60.0 ? 14 : 8;
                if (todayBull) {
                    t1Score += prevBull ? 15 : 8;
                }
                if (oiRisePct >= 10.0 && oiUpDays >= 3) {
                    t1Score += 5;
                }
                t1Score = Math.min(100, t1Score);
            }

            // === 轨道 2: [首波OI潜伏] / [首波试盘潜伏] ===
            List<Double> sortedCloses = new ArrayList<>(window.size());
            for (KlineData k : window) sortedCloses.add(k.getClosePrice());
            Collections.sort(sortedCloses);
            double pLowThreshold = percentileValue(sortedCloses, lowPricePercentile);
            double pDeepThreshold = percentileValue(sortedCloses, 25.0);
            double closePercentile = calcPercentileRank(sortedCloses, currentPrice);

            double close10dAgo = window.get(e - 10).getClosePrice();
            double chg10d = close10dAgo > 0 ? (currentPrice / close10dAgo - 1) * 100 : 100.0;

            int t2Score = 0;
            String t2Tag = "[首波OI潜伏]";
            double bestFpVolMult = 0;
            double dry3Ratio = 1.0;
            if (currentPrice <= pLowThreshold && Math.abs(chg10d) <= 15.0) {
                boolean hasOi = oiRisePct >= minOiRisePercent && oiUpDays >= 3;
                boolean hasFootprint = false;
                for (int i = Math.max(0, e - 14); i <= e - 2; i++) {
                    KlineData bar = window.get(i);
                    double vr = avgVol20 > 0 ? bar.getQuoteVolume() / avgVol20 : 0;
                    double barChg = bar.getOpenPrice() > 0
                            ? (bar.getClosePrice() / bar.getOpenPrice() - 1) * 100 : 0;
                    double barAmp = bar.getLowPrice() > 0
                            ? (bar.getHighPrice() - bar.getLowPrice()) / bar.getLowPrice() * 100 : 0;
                    if (vr >= 3.0 && barChg > -5.0 && barChg < 20.0 && barAmp >= 8.0) {
                        hasFootprint = true;
                        bestFpVolMult = Math.max(bestFpVolMult, vr);
                    }
                }
                double avgVol3 = (window.get(e).getQuoteVolume()
                        + window.get(e - 1).getQuoteVolume()
                        + window.get(e - 2).getQuoteVolume()) / 3.0;
                dry3Ratio = avgVol20 > 0 ? avgVol3 / avgVol20 : 1.0;
                boolean validFootprint = hasFootprint && dry3Ratio <= 0.65;

                if (hasOi || validFootprint) {
                    t2Score = 30;
                    if (hasOi) {
                        t2Score += oiRisePct >= 25.0 ? 35 : 25;
                    }
                    if (validFootprint) {
                        t2Score += dry3Ratio <= 0.45 ? 25 : 18;
                    }
                    t2Score += currentPrice <= pDeepThreshold ? 10 : 5;
                    if (todayBull) {
                        t2Score += 10;
                    }
                    t2Score = Math.min(100, t2Score);
                    t2Tag = (hasOi && validFootprint) ? "[首波OI+试盘]"
                            : hasOi ? "[首波OI潜伏]" : "[首波试盘潜伏]";
                }
            }

            int totalScore = Math.max(t1Score, t2Score);
            if (totalScore < minScore) continue;

            String detail;
            if (t1Score >= t2Score) {
                String tag = (oiRisePct >= 10.0 && oiUpDays >= 3) ? "[二波缩量+OI增]" : "[二波缩量坑]";
                double triggerVolUsdt = dry5Vol * 5.0;
                detail = String.format(
                        "蓄力%d %s | 前波5日%+.0f%%(%.1fx) 距前高%.4f回撤%.1f%% 近5日缩量至峰值%.1f%% 今日%+.1f%% 3日%+.1f%% | 盯盘点火: 放量站上%.4f 且 日额>%s",
                        totalScore, tag, legRet, legVolMult, swingHigh, drawdown, dryRatio * 100,
                        todayChg, chg3d, maxClose5, formatUsdt(triggerVolUsdt));
            } else {
                double triggerVolUsdt = avgVol20 * 2.0;
                String evidence = t2Tag.equals("[首波试盘潜伏]")
                        ? String.format("试盘%.1fx后近3日缩量%.2fx", bestFpVolMult, dry3Ratio)
                        : String.format("5日OI%+.1f%%(%d天增)", oiRisePct, oiUpDays);
                detail = String.format(
                        "蓄力%d %s | %d日%.0f%%分位 10日横盘%+.1f%% %s 今日%+.1f%% | 盯盘点火: 放量站上%.4f 且 日额>%s",
                        totalScore, t2Tag, lookbackDays, closePercentile, chg10d, evidence,
                        todayChg, maxClose5, formatUsdt(triggerVolUsdt));
            }

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(currentPrice)
                    .changePercent(chg3d)
                    .score(totalScore)
                    .detail(detail)
                    .build());
        }

        return matched;
    }

    private static double percentileValue(List<Double> sorted, double pct) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) (sorted.size() * pct / 100.0);
        return sorted.get(Math.min(Math.max(idx, 0), sorted.size() - 1));
    }

    private static double calcPercentileRank(List<Double> sorted, double value) {
        if (sorted.isEmpty()) return 50.0;
        int count = 0;
        for (double v : sorted) {
            if (v <= value) count++;
        }
        return count * 100.0 / sorted.size();
    }

    private static String formatUsdt(double usdt) {
        if (usdt >= 100_000_000) {
            return String.format("%.2f亿U", usdt / 100_000_000.0);
        }
        if (usdt >= 10_000) {
            return String.format("%.0f万U", usdt / 10_000.0);
        }
        return String.format("%.0fU", usdt);
    }
}
