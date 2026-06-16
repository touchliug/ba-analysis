package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.MarketFeature;
import com.ba.analyzer.service.MarketFeatureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * OI多周期分析器 - 监控不同时间段的持仓量变化, 发现主力建仓/出货痕迹。
 *
 * 复用 {@link MarketFeatureService} 的多窗口OI数据 (5m/1h/6h/24h/30d),
 * 取代旧实现中"用日线OI套5分钟时间戳"的错误算法。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OIMultiTFAnalyzer implements Analyzer {

    private final MarketFeatureService marketFeatureService;
    private final AppProperties appProperties;

    // 异动阈值
    private static final double ANOMALY_OI_H6 = 8.0;
    private static final double ANOMALY_OI_H24 = 15.0;
    private static final double ANOMALY_OI_D30 = 30.0;

    @Override public String getName() { return "OI多周期"; }
    @Override public boolean isEnabled() { return true; }
    @Override public boolean requiresIntradayData() { return true; }

    @Override
    public String getDescription() {
        return "监控OI在5分钟/1小时/6小时/24小时/30天的变化率, 发现主力建仓/出货痕迹";
    }

    @Override
    public AnalysisReport analyze(List<String> symbols) {
        return analyze(symbols, null);
    }

    @Override
    public AnalysisReport analyze(List<String> symbols, Map<String, Object> params) {
        Map<String, MarketFeature> features = marketFeatureService.computeFeatures(symbols);
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (MarketFeature f : features.values()) {
            if (!f.isOiAvailable()) continue;

            double oi6h = f.getOiChangeH6();
            double oi24h = f.getOiChangeH24();
            double oi30d = f.getOiChangeD30();

            boolean anomaly = Math.abs(oi6h) > ANOMALY_OI_H6
                    || Math.abs(oi24h) > ANOMALY_OI_H24
                    || Math.abs(oi30d) > ANOMALY_OI_D30;
            if (!anomaly) continue;

            String direction = oi24h > 10 ? "主力建仓" : oi24h < -10 ? "主力出货" : "异动";

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(f.getSymbol())
                    .currentPrice(f.getCurrentPrice())
                    .changePercent(oi24h)
                    .detail(String.format("%s | OI变化: 5m%+.1f%% 1h%+.1f%% 6h%+.1f%% 24h%+.1f%% 30d%+.1f%%",
                            direction, f.getOiChangeM5(), f.getOiChangeH1(), oi6h, oi24h, oi30d))
                    .build());
        }

        // 按OI 24h变化绝对值降序
        matched.sort(Comparator.comparingDouble(
                (AnalysisReport.CoinAnalysis c) -> Math.abs(c.getChangePercent())).reversed());

        return AnalysisReport.builder()
                .analysisType(getName())
                .analysisTime(LocalDateTime.now())
                .description("OI多周期异动监控")
                .coins(matched)
                .totalAnalyzed(symbols.size())
                .matchedCount(matched.size())
                .usedParams(Map.of("source", params == null ? "config" : "request"))
                .build();
    }
}
