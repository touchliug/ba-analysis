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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首阴日 —— 见顶做空信号。
 *
 * 形态: 连续 N 天以上阳线、累计涨幅达标后, 出现第一根(已收盘)阴线且跌破前日收盘。
 * 交易计划: 首阴次日开盘做空; 止损放在本轮冲高最高价上方(形态失效点); 止盈按入场价下方百分比。
 *
 * 只用已收盘K线判断信号: 当前正在走的日K(closeTime 在未来)仅取其开盘价作为入场价。
 * 日线批次 08:05(北京)= UTC 00:05 运行时, 首阴即为刚收盘的那一整天。
 *
 * 见顶强度分(0-100)= 超涨幅度(30)+首阴力度(25)+冲高回落上影(20)+放量(25),
 * 为经验档位, 尚未经回测校准, 用于排序; minScore 默认 0 不过滤。
 */
@Slf4j
@Component
public class FirstYinDayAnalyzer extends AbstractKlineAnalyzer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM-dd");

    public FirstYinDayAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    /** 需要当前未收盘根的开盘价作入场价, 自行区分收盘/未收盘。 */
    @Override
    protected boolean closedCandlesOnly() {
        return false;
    }

    @Override
    public Map<String, Object> getParamSpec() {
        var c = appProperties.getAnalysis().getFirstYinDay();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("days", Analyzer.param("int", "首阴前最少连续阳线天数", c.getDays()));
        s.put("minTotalRisePercent", Analyzer.param("double", "连涨累计涨幅下限%(复利)", c.getMinTotalRisePercent()));
        s.put("minYinDropPercent", Analyzer.param("double", "首阴相对昨收最小跌幅%", c.getMinYinDropPercent()));
        s.put("volumeSurgeRatio", Analyzer.param("double", "首阴量比下限(成交额/连涨期均额, 1.0=不过滤)", c.getVolumeSurgeRatio()));
        s.put("takeProfitPct", Analyzer.param("double", "止盈: 入场价下方%", c.getTakeProfitPct()));
        s.put("stopBufferPct", Analyzer.param("double", "止损: 冲高最高价上方缓冲%", c.getStopBufferPct()));
        s.put("maxStopLossPct", Analyzer.param("double", "入场到止损距离上限%, 超出不输出(0=不限)", c.getMaxStopLossPct()));
        s.put("minScore", Analyzer.param("int", "见顶强度分最低分(0-100, 0=不过滤)", c.getMinScore()));
        return s;
    }

    @Override
    public String getName() {
        return "首阴日";
    }

    @Override
    public String getDescription() {
        return buildDescription(getDefaultParams());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getFirstYinDay().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getFirstYinDay();
        return createParams(
                "days", cfg.getDays(),
                "minTotalRisePercent", cfg.getMinTotalRisePercent(),
                "minYinDropPercent", cfg.getMinYinDropPercent(),
                "volumeSurgeRatio", cfg.getVolumeSurgeRatio(),
                "takeProfitPct", cfg.getTakeProfitPct(),
                "stopBufferPct", cfg.getStopBufferPct(),
                "maxStopLossPct", cfg.getMaxStopLossPct(),
                "minScore", cfg.getMinScore());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getFirstYinDay();
        return createParams(
                "days", getIntParam(requestParams, "days", cfg.getDays()),
                "minTotalRisePercent", getDoubleParam(requestParams, "minTotalRisePercent", cfg.getMinTotalRisePercent()),
                "minYinDropPercent", getDoubleParam(requestParams, "minYinDropPercent", cfg.getMinYinDropPercent()),
                "volumeSurgeRatio", getDoubleParam(requestParams, "volumeSurgeRatio", cfg.getVolumeSurgeRatio()),
                "takeProfitPct", getDoubleParam(requestParams, "takeProfitPct", cfg.getTakeProfitPct()),
                "stopBufferPct", getDoubleParam(requestParams, "stopBufferPct", cfg.getStopBufferPct()),
                "maxStopLossPct", getDoubleParam(requestParams, "maxStopLossPct", cfg.getMaxStopLossPct()),
                "minScore", getIntParam(requestParams, "minScore", cfg.getMinScore()));
    }

    @Override
    protected Map<String, List<KlineData>> fetchData(List<String> symbols, Map<String, Object> params) {
        int days = (int) params.get("days");
        // 首阴 + 连涨区间 + 区间前一根(基准价) + 当前未收盘根; 缓冲用于识别比 days 更长的连涨。
        return dataFetchService.fetchDailyKlines(symbols, days + 3 + getHistoryBufferDays());
    }

    @Override
    protected void sortResults(List<AnalysisReport.CoinAnalysis> matched) {
        matched.sort(Comparator.comparing(
                (AnalysisReport.CoinAnalysis c) -> c.getScore() == null ? 0 : c.getScore(),
                Comparator.reverseOrder()));
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("见顶做空: 连涨≥%d天(累涨≥%.0f%%)后首根阴线(跌≥%.1f%%), "
                        + "次日开盘做空, 止盈%.0f%%, 止损设在冲高最高价上方%.1f%%",
                (int) params.get("days"), (double) params.get("minTotalRisePercent"),
                (double) params.get("minYinDropPercent"), (double) params.get("takeProfitPct"),
                (double) params.get("stopBufferPct"));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        double minTotalRise = (double) params.get("minTotalRisePercent");
        double minYinDrop = (double) params.get("minYinDropPercent");
        double volumeSurgeRatio = (double) params.get("volumeSurgeRatio");
        double takeProfitPct = (double) params.get("takeProfitPct");
        double stopBufferPct = (double) params.get("stopBufferPct");
        double maxStopLossPct = (double) params.get("maxStopLossPct");
        int minScore = (int) params.get("minScore");

        long now = System.currentTimeMillis();
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.isEmpty()) continue;

            // 拆出当前未收盘根: 只取其开盘价作入场, 不参与形态判断。
            int yinIdx = klines.size() - 1;
            KlineData current = null;
            if (klines.get(yinIdx).getCloseTime() > now) {
                current = klines.get(yinIdx);
                yinIdx--;
            }
            // 至少需要: 基准根 + days 根阳线 + 首阴
            if (yinIdx < days + 1) continue;

            KlineData yin = klines.get(yinIdx);
            // 日线同步滞后时最新已收盘根不是"昨天", 信号会错位 → 跳过
            if (yin.getCloseTime() > 0 && now - yin.getCloseTime() > DAY_MS) continue;
            if (!isBearish(yin)) continue;

            KlineData lastRise = klines.get(yinIdx - 1);
            if (!isBullish(lastRise)) continue;
            if (yin.getClosePrice() >= lastRise.getClosePrice()) continue;

            // 向前延伸统计实际连涨天数 (可能多于 days)
            int runStart = yinIdx - 1;
            while (runStart > 0 && isBullish(klines.get(runStart - 1))) runStart--;
            int runDays = yinIdx - runStart;
            if (runDays < days) continue;

            double refPrice = runStart > 0
                    ? klines.get(runStart - 1).getClosePrice()
                    : klines.get(runStart).getOpenPrice();
            if (refPrice <= 0 || lastRise.getClosePrice() <= 0) continue;
            double totalRise = (lastRise.getClosePrice() / refPrice - 1) * 100;
            if (totalRise < minTotalRise) continue;

            double yinDrop = (lastRise.getClosePrice() - yin.getClosePrice()) / lastRise.getClosePrice() * 100;
            if (yinDrop < minYinDrop) continue;

            double runVolSum = 0;
            double runHigh = yin.getHighPrice();
            for (int i = runStart; i < yinIdx; i++) {
                runVolSum += klines.get(i).getQuoteVolume();
                runHigh = Math.max(runHigh, klines.get(i).getHighPrice());
            }
            double runAvgVol = runVolSum / runDays;
            double volRatio = runAvgVol > 0 ? yin.getQuoteVolume() / runAvgVol : 0;
            if (volumeSurgeRatio > 1.0 && volRatio < volumeSurgeRatio) continue;

            double yinRange = yin.getHighPrice() - yin.getLowPrice();
            double upperWick = yinRange > 0
                    ? (yin.getHighPrice() - Math.max(yin.getOpenPrice(), yin.getClosePrice())) / yinRange
                    : 0;

            // ===== 做空计划 =====
            boolean nextOpen = current != null && current.getOpenPrice() > 0;
            double entryPrice = nextOpen ? current.getOpenPrice() : yin.getClosePrice();
            double stopPrice = runHigh * (1 + stopBufferPct / 100);
            double stopPct = (stopPrice - entryPrice) / entryPrice * 100;
            if (stopPct <= 0) continue;
            if (maxStopLossPct > 0 && stopPct > maxStopLossPct) continue;
            double targetPrice = entryPrice * (1 - takeProfitPct / 100);
            double riskReward = takeProfitPct / stopPct;

            TopScore s = scoreTop(totalRise, yinDrop, upperWick, volRatio);
            if (minScore > 0 && s.total < minScore) continue;

            Map<String, Object> breakdown = new LinkedHashMap<>();
            breakdown.put("rise", s.riseScore);
            breakdown.put("yin", s.yinScore);
            breakdown.put("wick", s.wickScore);
            breakdown.put("volume", s.volumeScore);

            String yinDate = Instant.ofEpochMilli(yin.getOpenTime())
                    .atOffset(ZoneOffset.ofHours(8)).format(DATE_FMT);

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(entryPrice)
                    .changePercent(-yinDrop)
                    .score(s.total)
                    .side("short")
                    .scoreBreakdown(breakdown)
                    .detail(String.format(
                            "[%s] 连涨%d天累涨%.1f%% 首阴跌%.1f%%(量比%.1fx 上影%.0f%%) "
                                    + "见顶分%d(超涨%d/阴线%d/上影%d/放量%d) "
                                    + "| 做空 入场%.4f(%s) 止盈%.4f(-%.0f%%) 止损%.4f(+%.1f%%) 盈亏比%.1f",
                            yinDate, runDays, totalRise, yinDrop, volRatio, upperWick * 100,
                            s.total, s.riseScore, s.yinScore, s.wickScore, s.volumeScore,
                            entryPrice, nextOpen ? "次日开盘" : "首阴收盘",
                            targetPrice, takeProfitPct, stopPrice, stopPct, riskReward))
                    .build());
        }
        return matched;
    }

    private static boolean isBullish(KlineData k) {
        return k.getClosePrice() > k.getOpenPrice();
    }

    private static boolean isBearish(KlineData k) {
        return k.getClosePrice() < k.getOpenPrice();
    }

    /** 见顶强度分拆解。total = 超涨(30)+首阴力度(25)+上影(20)+放量(25)。 */
    static class TopScore {
        int riseScore;
        int yinScore;
        int wickScore;
        int volumeScore;
        int total;
    }

    static TopScore scoreTop(double totalRise, double yinDrop, double upperWick, double volRatio) {
        TopScore s = new TopScore();
        // 超涨越多, 获利盘回吐压力越大
        if (totalRise >= 60) s.riseScore = 30;
        else if (totalRise >= 40) s.riseScore = 24;
        else if (totalRise >= 25) s.riseScore = 16;
        else s.riseScore = 8;
        // 首阴越深, 多头溃败越明确
        if (yinDrop >= 10) s.yinScore = 25;
        else if (yinDrop >= 6) s.yinScore = 19;
        else if (yinDrop >= 3) s.yinScore = 12;
        else s.yinScore = 5;
        // 长上影 = 冲高后被砸回, 高位抛压
        if (upperWick >= 0.4) s.wickScore = 20;
        else if (upperWick >= 0.25) s.wickScore = 13;
        else if (upperWick >= 0.1) s.wickScore = 6;
        else s.wickScore = 0;
        // 放量下跌 = 主力出货
        if (volRatio >= 2.0) s.volumeScore = 25;
        else if (volRatio >= 1.5) s.volumeScore = 18;
        else if (volRatio >= 1.0) s.volumeScore = 10;
        else s.volumeScore = 3;
        s.total = s.riseScore + s.yinScore + s.wickScore + s.volumeScore;
        return s;
    }
}
