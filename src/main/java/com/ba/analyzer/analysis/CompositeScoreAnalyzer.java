package com.ba.analyzer.analysis;

import com.ba.analyzer.analysis.support.PullbackGate;
import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.MarketFeature;
import com.ba.analyzer.service.MarketFeatureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 综合评分层 (Phase 4, 复刻 sanhe6 的 6 维加权 + 分层漏斗)。
 *
 * 解决"14个分析器各出各报告、无法横向比较"的问题: 用统一的多窗口特征对全市场打 0-100 分并排序。
 * 维度权重(默认): OI 25 / 价格 20 / 费率 15 / 量能 15 / 社媒 0(预留) / 多交易所 0(预留)。
 * 叠加 {@link PullbackGate} 定 stage (追高保护), 输出按综合分降序。
 */
@Slf4j
@Component
public class CompositeScoreAnalyzer implements Analyzer {

    private final MarketFeatureService marketFeatureService;
    private final AppProperties appProperties;

    public CompositeScoreAnalyzer(MarketFeatureService marketFeatureService, AppProperties appProperties) {
        this.marketFeatureService = marketFeatureService;
        this.appProperties = appProperties;
    }

    @Override
    public String getName() {
        return "综合评分";
    }

    @Override
    public String getDescription() {
        var cfg = appProperties.getAnalysis().getCompositeScore();
        return String.format("多窗口6维加权评分(OI%d/价%d/费率%d/量%d)+回踩门, 最低分%d, 全市场排序",
                cfg.getOiWeight(), cfg.getPriceWeight(), cfg.getFundingWeight(),
                cfg.getVolumeWeight(), cfg.getMinScore());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getCompositeScore().isEnabled();
    }

    @Override
    public boolean requiresIntradayData() {
        return true;
    }

    @Override
    public AnalysisReport analyze(List<String> symbols) {
        return analyze(symbols, null);
    }

    @Override
    public AnalysisReport analyze(List<String> symbols, Map<String, Object> params) {
        var cfg = appProperties.getAnalysis().getCompositeScore();
        int minScore = getIntParam(params, "minScore", cfg.getMinScore());

        Map<String, MarketFeature> features = marketFeatureService.computeFeatures(symbols);

        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();
        for (MarketFeature f : features.values()) {
            ScoreResult sr = score(f, cfg);
            if (sr.total < minScore) continue;

            PullbackGate.GateResult gate = PullbackGate.evaluate(f);

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(f.getSymbol())
                    .currentPrice(f.getCurrentPrice())
                    .changePercent(f.getPriceChangeH24())
                    .score(sr.total)
                    .stage(gate.stage().name())
                    .side(decideSide(f, gate))
                    .scoreBreakdown(sr.breakdown)
                    .detail(String.format(
                            "综合%d分 [%s] OI(6h%.1f%%/24h%.1f%%/30d%.1f%%) 价(6h%.1f%%/24h%.1f%%) 量6h×%.1f 费率%.4f%s%s | %s",
                            sr.total, gate.stage().name(),
                            f.getOiChangeH6(), f.getOiChangeH24(), f.getOiChangeD30(),
                            f.getPriceChangeH6(), f.getPriceChangeH24(),
                            f.getVolChangeH6(), f.getFundingRate(),
                            f.isContractIgnition() ? " 合约点火" : "",
                            f.isVolumeRelay() ? " 量能接力" : "",
                            gate.reason()))
                    .build());
        }

        matched.sort(Comparator.comparing(
                (AnalysisReport.CoinAnalysis c) -> c.getScore() == null ? 0 : c.getScore(),
                Comparator.reverseOrder()));

        Map<String, Object> used = new LinkedHashMap<>();
        used.put("minScore", minScore);
        used.put("oiWeight", cfg.getOiWeight());
        used.put("priceWeight", cfg.getPriceWeight());
        used.put("fundingWeight", cfg.getFundingWeight());
        used.put("volumeWeight", cfg.getVolumeWeight());
        used.put("source", params == null ? "config" : "request");

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

    private static class ScoreResult {
        int total;
        Map<String, Object> breakdown = new LinkedHashMap<>();
    }

    /** 6维加权评分。各维度先算 0-1 命中度, 再乘权重。 */
    private ScoreResult score(MarketFeature f, AppProperties.CompositeScoreConfig cfg) {
        ScoreResult r = new ScoreResult();

        // OI维度: 6h增幅为主(0~+20%线性), 叠加30d低位加成
        double oiHit = 0;
        if (f.isOiAvailable()) {
            oiHit = clamp01(f.getOiChangeH6() / 20.0);
            if (f.getOiChangeD30() <= 0) oiHit = Math.min(1.0, oiHit + 0.15); // 30d低位加成
        }
        int oiScore = (int) Math.round(oiHit * cfg.getOiWeight());

        // 价格维度: "早期"最优 — 24h涨幅在 0~earlyPriceMax 之间给高分, 超出递减(防追高), 暴跌给低分
        double p24 = f.getPriceChangeH24();
        double priceHit;
        double earlyMax = cfg.getEarlyPriceMaxPct();
        if (p24 < 0) {
            priceHit = Math.max(0, 1 + p24 / 30.0); // 跌越多分越低, -30%归零
        } else if (p24 <= earlyMax) {
            priceHit = 1.0; // 早期窗口, 满分
        } else {
            priceHit = Math.max(0, 1 - (p24 - earlyMax) / earlyMax); // 超早期窗口递减
        }
        int priceScore = (int) Math.round(priceHit * cfg.getPriceWeight());

        // 费率维度: 越接近中性越健康 (|rate|<=0.0005 满分, 线性衰减到 0.003)
        double absFund = Math.abs(f.getFundingRate());
        double fundHit = absFund <= 0.0005 ? 1.0 : Math.max(0, 1 - (absFund - 0.0005) / 0.0025);
        int fundScore = (int) Math.round(fundHit * cfg.getFundingWeight());

        // 量能维度: 近6h/前6h 放大 (1x→0, 3x→满分)
        double volHit = clamp01((f.getVolChangeH6() - 1.0) / 2.0);
        int volScore = (int) Math.round(volHit * cfg.getVolumeWeight());

        r.total = Math.min(100, oiScore + priceScore + fundScore + volScore);
        r.breakdown.put("oi", oiScore);
        r.breakdown.put("price", priceScore);
        r.breakdown.put("funding", fundScore);
        r.breakdown.put("volume", volScore);
        r.breakdown.put("social", 0);
        r.breakdown.put("venue", 0);
        return r;
    }

    private String decideSide(MarketFeature f, PullbackGate.GateResult gate) {
        return switch (gate.stage()) {
            case ENTRY, SETUP -> "long";
            case AVOID -> "none";
            default -> "watch";
        };
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1.0, v));
    }
}
