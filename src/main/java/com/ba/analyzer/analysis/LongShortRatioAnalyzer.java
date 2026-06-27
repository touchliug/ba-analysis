package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多空比异动分析器。
 *
 * 信号 = 大户持仓多空比(TOP_POSITION)抬升 + taker主动买盘占优(TAKER>阈值)
 *        + 与散户账户多空比(GLOBAL_ACCOUNT)背离(大户进、散户退)。
 * 默认启用, requiresIntradayData=true, 随 short-term 批次每5分钟运行。
 * LSR 数据缺失时优雅跳过对应 symbol, 不报错。
 */
@Slf4j
@Component
public class LongShortRatioAnalyzer implements Analyzer {

    private final DataFetchService dataFetchService;
    private final AppProperties appProperties;

    public LongShortRatioAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        this.dataFetchService = dataFetchService;
        this.appProperties = appProperties;
    }

    @Override
    public String getName() {
        return "多空比异动";
    }

    @Override
    public String getDescription() {
        var cfg = appProperties.getAnalysis().getLongShortRatio();
        return String.format("大户持仓多空比抬升+taker买盘占优+与散户背离, 评分≥%d (period=%s, lookback=%d)",
                cfg.getMinScore(), cfg.getPeriod(), cfg.getLookback());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getLongShortRatio().isEnabled();
    }

    @Override
    public boolean requiresIntradayData() {
        return true;
    }

    @Override
    public AnalysisReport analyze(List<String> symbols) {
        var cfg = appProperties.getAnalysis().getLongShortRatio();
        String period = cfg.getPeriod();
        int limit = cfg.getLookback() + 1;

        Map<String, List<LongShortRatioData>> topPos =
                dataFetchService.getLongShortRatioBatch(symbols, "TOP_POSITION", period, limit);
        Map<String, List<LongShortRatioData>> global =
                dataFetchService.getLongShortRatioBatch(symbols, "GLOBAL_ACCOUNT", period, limit);
        Map<String, List<LongShortRatioData>> taker =
                dataFetchService.getLongShortRatioBatch(symbols, "TAKER", period, 1);

        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();
        for (String symbol : symbols) {
            List<LongShortRatioData> tp = topPos.get(symbol);
            List<LongShortRatioData> tk = taker.get(symbol);
            if (tp == null || tp.size() < cfg.getLookback() + 1) {
                log.debug("skip {}: TOP_POSITION series insufficient ({} < {})",
                        symbol, tp == null ? 0 : tp.size(), cfg.getLookback() + 1);
                continue;
            }
            if (tk == null || tk.isEmpty()) {
                log.debug("skip {}: TAKER data missing", symbol);
                continue;
            }

            double agoTP = tp.get(0).getRatioValue();
            double latestTP = tp.get(tp.size() - 1).getRatioValue();
            double tpChangePct = agoTP > 0 ? (latestTP - agoTP) / agoTP * 100 : 0;

            double latestTaker = tk.get(tk.size() - 1).getRatioValue();

            double gaChangePct = 0;
            List<LongShortRatioData> ga = global.get(symbol);
            if (ga != null && ga.size() >= 2) {
                double agoGA = ga.get(0).getRatioValue();
                double latestGA = ga.get(ga.size() - 1).getRatioValue();
                gaChangePct = agoGA > 0 ? (latestGA - agoGA) / agoGA * 100 : 0;
            }

            double tpHit = clamp01(tpChangePct / 10.0);
            // 饱和区间=1.0: taker 比 takerBullishMin 高出 1.0 即满分
            double takerHit = clamp01((latestTaker - cfg.getTakerBullishMin()) / 1.0);
            double divHit = (tpChangePct > 0 && gaChangePct < 0)
                    ? clamp01((tpChangePct - gaChangePct) / 20.0) : 0;

            int score = (int) Math.min(100, Math.round(
                    tpHit * cfg.getTopPositionWeight()
                            + takerHit * cfg.getTakerWeight()
                            + divHit * cfg.getDivergenceWeight()));
            if (score < cfg.getMinScore()) continue;

            String side = (tpChangePct > 0 && latestTaker >= cfg.getTakerBullishMin()) ? "long" : "watch";

            Map<String, Object> breakdown = new LinkedHashMap<>();
            breakdown.put("topPosition", (int) Math.round(tpHit * cfg.getTopPositionWeight()));
            breakdown.put("taker", (int) Math.round(takerHit * cfg.getTakerWeight()));
            breakdown.put("divergence", (int) Math.round(divHit * cfg.getDivergenceWeight()));

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .changePercent(tpChangePct)
                    .score(score)
                    .side(side)
                    .scoreBreakdown(breakdown)
                    .detail(String.format(
                            "多空比异动%d分 大户持仓%.2f(%+.1f%%) taker%.2f 散户%+.1f%%",
                            score, latestTP, tpChangePct, latestTaker, gaChangePct))
                    .build());
        }

        matched.sort(Comparator.comparing(
                (AnalysisReport.CoinAnalysis c) -> c.getScore() == null ? 0 : c.getScore(),
                Comparator.reverseOrder()));

        Map<String, Object> used = new LinkedHashMap<>();
        used.put("period", period);
        used.put("lookback", cfg.getLookback());
        used.put("minScore", cfg.getMinScore());
        used.put("source", "config");

        return AnalysisReport.builder()
                .analysisType(getName())
                .analysisTime(LocalDateTime.now())
                .description(getDescription())
                .coins(matched)
                .totalAnalyzed(symbols.size())
                .matchedCount(matched.size())
                .usedParams(used)
                .build();
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1.0, v));
    }
}
