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
 * 反转做多分析器 (基于历史回测优化)
 *
 * 策略: 连跌N天 → 小反转 → 第1天开盘做多
 * 回测结果(2026/01-06, 527币种, 365笔):
 *   胜率65.8%, 均值+1.46%, 盈亏比3.52
 */
@Slf4j
@Component
public class ReversalLongAnalyzer extends AbstractKlineAnalyzer {

    public ReversalLongAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "反转做多";
    }

    @Override
    public String getDescription() {
        var cfg = appProperties.getAnalysis().getReversalLong();
        return String.format("连跌>=%d天(%.0f-%.0f%%)+反转%.0f-%.0f%%, 第1天开盘做多, 止盈%.0f%%止损%.0f%%",
                cfg.getDeclineMinDays(), cfg.getDeclineMinPct(), cfg.getDeclineMaxPct(),
                cfg.getReversalMinPct(), cfg.getReversalMaxPct(),
                cfg.getTakeProfitPct(), cfg.getStopLossPct());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getReversalLong().isEnabled();
    }

    @Override
    public int requiredDays() {
        // 连跌天数 + 反转日 + 入场日 + 余量
        return appProperties.getAnalysis().getReversalLong().getDeclineMinDays() + 5;
    }

    @Override
    protected Map<String, List<KlineData>> fetchData(List<String> symbols, Map<String, Object> params) {
        // 反转做多无"days"参数, 父类默认会回退到7天; 这里按连跌天数取数, 与 requiredDays() 口径一致。
        int declineMinDays = (int) params.get("declineMinDays");
        int needed = declineMinDays + 5 + getHistoryBufferDays();
        return dataFetchService.fetchDailyKlines(symbols, needed);
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getReversalLong();
        return createParams(
                "declineMinDays", cfg.getDeclineMinDays(),
                "declineMinPct", cfg.getDeclineMinPct(),
                "declineMaxPct", cfg.getDeclineMaxPct(),
                "reversalMinPct", cfg.getReversalMinPct(),
                "reversalMaxPct", cfg.getReversalMaxPct(),
                "takeProfitPct", cfg.getTakeProfitPct(),
                "stopLossPct", cfg.getStopLossPct(),
                "volumeConfirmRatio", cfg.getVolumeConfirmRatio(),
                "qualityMinScore", cfg.getQualityMinScore());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getReversalLong();
        return createParams(
                "declineMinDays", getIntParam(requestParams, "declineMinDays", cfg.getDeclineMinDays()),
                "declineMinPct", getDoubleParam(requestParams, "declineMinPct", cfg.getDeclineMinPct()),
                "declineMaxPct", getDoubleParam(requestParams, "declineMaxPct", cfg.getDeclineMaxPct()),
                "reversalMinPct", getDoubleParam(requestParams, "reversalMinPct", cfg.getReversalMinPct()),
                "reversalMaxPct", getDoubleParam(requestParams, "reversalMaxPct", cfg.getReversalMaxPct()),
                "takeProfitPct", getDoubleParam(requestParams, "takeProfitPct", cfg.getTakeProfitPct()),
                "stopLossPct", getDoubleParam(requestParams, "stopLossPct", cfg.getStopLossPct()),
                "volumeConfirmRatio", getDoubleParam(requestParams, "volumeConfirmRatio", cfg.getVolumeConfirmRatio()),
                "qualityMinScore", getIntParam(requestParams, "qualityMinScore", cfg.getQualityMinScore()));
    }

    @Override
    protected void sortResults(List<AnalysisReport.CoinAnalysis> matched) {
        // 按衰竭分降序排序 (分越高 = 放量+长下影+止跌翻红特征越显著, 越接近验证过的盈利形态)
        matched.sort(Comparator.comparing(
                (AnalysisReport.CoinAnalysis c) -> c.getScore() == null ? 0 : c.getScore(),
                Comparator.reverseOrder()));
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("连跌>=%d天(%.0f-%.0f%%)+反转%.0f-%.0f%%, 第1天开盘做多, 止盈%.0f%%止损%.0f%%",
                (int) params.get("declineMinDays"),
                (double) params.get("declineMinPct"), (double) params.get("declineMaxPct"),
                (double) params.get("reversalMinPct"), (double) params.get("reversalMaxPct"),
                (double) params.get("takeProfitPct"), (double) params.get("stopLossPct"));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int declineMinDays = (int) params.get("declineMinDays");
        double declineMinPct = (double) params.get("declineMinPct");
        double declineMaxPct = (double) params.get("declineMaxPct");
        double reversalMinPct = (double) params.get("reversalMinPct");
        double reversalMaxPct = (double) params.get("reversalMaxPct");
        double takeProfitPct = (double) params.get("takeProfitPct");
        double stopLossPct = (double) params.get("stopLossPct");
        double volumeConfirmRatio = (double) params.get("volumeConfirmRatio");
        int qualityMinScore = (int) params.get("qualityMinScore");

        int minNeeded = declineMinDays + 2; // decline days + reversal + entry
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();
        long now = System.currentTimeMillis();
        long oneDayMs = 24L * 60 * 60 * 1000;

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.size() < minNeeded) continue;

            List<KlineData> complete = new ArrayList<>(klines);
            if (complete.size() < minNeeded) continue;

            // 入场日 = 最新一根K线 (应为当前在交易的当日K线: 反转/跌势都用其之前的已收盘日)。
            int entryIdx = complete.size() - 1;

            // 数据新鲜度校验: 若最新K线收盘时间已过去超过1天, 说明日线同步滞后,
            // 此时 entryIdx 实际指向的是旧日, 整个信号会错位一天 → 跳过该币。
            // (当日K线未收盘时 closeTime 在未来, now-closeTime 为负, 正常通过。)
            long newestCloseTime = complete.get(entryIdx).getCloseTime();
            if (newestCloseTime > 0 && now - newestCloseTime > oneDayMs) continue;

                int reversalIdx = entryIdx - 1;
                int declineEndIdx = entryIdx - 2;
                int declineStartIdx = declineEndIdx - declineMinDays + 1;
                if (declineStartIdx < 0) continue;

                // Verify decline: consecutive down days
                boolean allDown = true;
                // The day before decline starts is the reference price
                int refIdx = declineStartIdx - 1;
                if (refIdx < 0) continue;
                double refPrice = complete.get(refIdx).getClosePrice();
                if (refPrice <= 0) continue;

                for (int d = declineStartIdx; d <= declineEndIdx; d++) {
                    if (complete.get(d).getClosePrice() >= complete.get(d - 1).getClosePrice()) {
                        allDown = false;
                        break;
                    }
                }
                if (!allDown) continue;

                // Check decline is at least declineMinDays (already verified by loop range)
                int actualDeclineDays = declineEndIdx - declineStartIdx + 1;
                // Also extend: there might be more down days before declineStartIdx
                int extStart = declineStartIdx;
                while (extStart > 0 && complete.get(extStart - 1).getClosePrice() < complete.get(extStart).getClosePrice()) {
                    extStart--;
                    actualDeclineDays++;
                }
                if (actualDeclineDays < declineMinDays) continue;

                // Recalculate reference price from the extended start
                refIdx = extStart - 1;
                if (refIdx < 0) continue;
                refPrice = complete.get(refIdx).getClosePrice();

                double declineEndPrice = complete.get(declineEndIdx).getClosePrice();
                double declinePct = Math.abs((declineEndPrice - refPrice) / refPrice * 100);
                if (declinePct < declineMinPct || declinePct > declineMaxPct) continue;

                // Verify reversal day
                KlineData reversalDay = complete.get(reversalIdx);
                double reversalPct = (reversalDay.getClosePrice() - declineEndPrice) / declineEndPrice * 100;
                if (reversalPct < reversalMinPct || reversalPct > reversalMaxPct) continue;
                if (reversalDay.getClosePrice() <= complete.get(declineEndIdx).getClosePrice()) continue;

                // Volume confirmation: reversal day volume vs decline period average
                double declineVolSum = 0;
                int declineVolCount = 0;
                for (int d = extStart; d <= declineEndIdx; d++) {
                    declineVolSum += complete.get(d).getQuoteVolume();
                    declineVolCount++;
                }
                double avgDeclineVol = declineVolCount > 0 ? declineVolSum / declineVolCount : 0;
                double revVolRatio = avgDeclineVol > 0 ? reversalDay.getQuoteVolume() / avgDeclineVol : 0;
                if (volumeConfirmRatio > 1.0 && revVolRatio < volumeConfirmRatio) continue;

                // ===== 反转质量评分 (0-100) =====
                // 验证依据: research/backtest_reversal_long.py 对531币种、2739笔信号的全量回测。
                // 有效因子(胜率越高越好): 连跌5天 > 跌幅6-7% > 反转涨幅1-1.5% > 短下影 > 收盘落在下半区。
                // (旧的"放量+长下影"评分经回测证实为反向, 已废弃。)
                QualityScore q = scoreQuality(
                        actualDeclineDays, declinePct, reversalPct, reversalDay);

                if (qualityMinScore > 0 && q.total < qualityMinScore) continue;

                // Signal found!
                KlineData entryDay = complete.get(entryIdx);
                double entryPrice = entryDay.getOpenPrice();
                double targetPrice = entryPrice * (1 + takeProfitPct / 100);
                double stopPrice = entryPrice * (1 - stopLossPct / 100);
                double riskReward = takeProfitPct / stopLossPct;

                // Format entry date
                java.time.LocalDateTime entryDate = java.time.LocalDateTime.ofEpochSecond(
                        entryDay.getOpenTime() / 1000, 0, java.time.ZoneOffset.ofHours(8));
                String dateStr = entryDate.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"));

                matched.add(AnalysisReport.CoinAnalysis.builder()
                        .symbol(symbol)
                        .currentPrice(entryPrice)
                        .changePercent(reversalPct)
                        .score(q.total)
                        .detail(String.format(
                                "[%s] 连跌%d天累跌%.1f%% 反转+%.1f%%(量比%.1fx) 质量分%d(跌势%d/跌幅%d/反转%d/下影%d/位置%d) "
                                        + "| 入场%.4f 止盈%.4f(+%.0f%%) 止损%.4f(-%.0f%%) 盈亏比%.1f",
                                dateStr, actualDeclineDays, declinePct, reversalPct, revVolRatio,
                                q.total, q.daysScore, q.dropScore, q.revScore, q.wickScore, q.posScore,
                                entryPrice, targetPrice, takeProfitPct, stopPrice, stopLossPct, riskReward))
                        .build());
        }

        return matched;
    }

    /** 反转质量评分拆解。total = 跌势天数(25)+跌幅(20)+反转涨幅(20)+下影(20)+收盘位置(15)。 */
    private static class QualityScore {
        int daysScore;
        int dropScore;
        int revScore;
        int wickScore;
        int posScore;
        int total;
    }

    /**
     * 计算反转质量评分(0-100)。各档位权重由 2739 笔全量回测校准
     * (research/backtest_reversal_long.py + factor_explore.py):
     * 分越高, 回测胜率与盈亏比越高 (score>=55 胜率55%, >=75 胜率62%, >=85 胜率71%)。
     *
     * @param declineDays 连跌天数
     * @param declinePct  累计跌幅(%, 正数)
     * @param reversalPct 反转日涨幅(%)
     * @param reversalDay 反转日K线
     */
    private QualityScore scoreQuality(int declineDays, double declinePct,
                                      double reversalPct, KlineData reversalDay) {
        QualityScore s = new QualityScore();

        // 1) 连跌天数 (0-25): 5天最佳, 4/6次之, 7+天接飞刀
        s.daysScore = switch (declineDays) {
            case 5 -> 25;
            case 6 -> 15;
            case 4 -> 12;
            default -> 0; // >=7 或 <4
        };

        // 2) 累计跌幅 (0-20): 6-7%最佳, 越大越差
        if (declinePct < 6) s.dropScore = 15;
        else if (declinePct < 7) s.dropScore = 20;
        else if (declinePct < 8) s.dropScore = 15;
        else if (declinePct < 9) s.dropScore = 10;
        else s.dropScore = 3;

        // 3) 反转日涨幅 (0-20): 越温和越好 (1-1.5%最佳)
        if (reversalPct < 1.5) s.revScore = 20;
        else if (reversalPct < 2) s.revScore = 16;
        else if (reversalPct < 2.5) s.revScore = 10;
        else if (reversalPct < 3) s.revScore = 5;
        else s.revScore = 0;

        // 反转日形态
        double high = reversalDay.getHighPrice();
        double low = reversalDay.getLowPrice();
        double open = reversalDay.getOpenPrice();
        double close = reversalDay.getClosePrice();
        double range = high - low;
        double bodyLow = Math.min(open, close);

        // 4) 下影占比 (0-20): 越短越好 (回测证实长下影是反向信号)
        double lowerWick = range > 0 ? (bodyLow - low) / range : 0;
        if (lowerWick < 0.1) s.wickScore = 20;
        else if (lowerWick < 0.2) s.wickScore = 12;
        else if (lowerWick < 0.3) s.wickScore = 4;
        else s.wickScore = 0;

        // 5) 收盘位置 (0-15): 收在当日下半区最好 (温和试探优于冲高)
        double closePos = range > 0 ? (close - low) / range : 0.5;
        if (closePos < 0.5) s.posScore = 15;
        else if (closePos < 0.7) s.posScore = 8;
        else s.posScore = 0;

        s.total = Math.min(100, s.daysScore + s.dropScore + s.revScore + s.wickScore + s.posScore);
        return s;
    }
}
