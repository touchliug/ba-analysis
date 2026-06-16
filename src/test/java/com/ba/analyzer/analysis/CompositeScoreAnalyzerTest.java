package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.MarketFeature;
import com.ba.analyzer.service.MarketFeatureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeScoreAnalyzerTest {

    @Mock
    private MarketFeatureService marketFeatureService;
    @Mock
    private AppProperties appProperties;

    private CompositeScoreAnalyzer analyzer;

    @BeforeEach
    void setUp() {
		// test github
        AppProperties.AnalysisConfig ac = new AppProperties.AnalysisConfig();
        ac.setCompositeScore(new AppProperties.CompositeScoreConfig());
        when(appProperties.getAnalysis()).thenReturn(ac);
        analyzer = new CompositeScoreAnalyzer(marketFeatureService, appProperties);
    }

    private MarketFeature strong(String sym) {
        // 早期(24h+6%) + OI 6h点火 + 30d低位 + 量能接力 + 费率中性 → 高分
        return MarketFeature.builder()
                .symbol(sym).currentPrice(1.0)
                .oiAvailable(true)
                .oiChangeH6(20).oiChangeH24(16).oiChangeD30(-50).oiChangeM5(0.5)
                .priceChangeH6(4).priceChangeH24(6)
                .volChangeH6(4.0).fundingRate(0.00005)
                .contractIgnition(true).volumeRelay(true).fundingHealthy(true)
                .build();
    }

    private MarketFeature weak(String sym) {
        // 暴跌中 + OI塌 + 缩量 → 低分
        return MarketFeature.builder()
                .symbol(sym).currentPrice(1.0)
                .oiAvailable(true)
                .oiChangeH6(-8).oiChangeH24(-10).oiChangeD30(5).oiChangeM5(-2)
                .priceChangeH6(-5).priceChangeH24(-25)
                .volChangeH6(0.8).fundingRate(0.002)
                .build();
    }

    @Test
    void strongScoresHigh_weakFiltered() {
        when(marketFeatureService.computeFeatures(anyList()))
                .thenReturn(Map.of("AAAUSDT", strong("AAAUSDT"), "BBBUSDT", weak("BBBUSDT")));

        AnalysisReport r = analyzer.analyze(List.of("AAAUSDT", "BBBUSDT"));
        // 弱的应被 minScore=50 过滤, 只剩强的
        assertEquals(1, r.getCoins().size());
        AnalysisReport.CoinAnalysis top = r.getCoins().get(0);
        assertEquals("AAAUSDT", top.getSymbol());
        assertTrue(top.getScore() >= 50, "强信号综合分应>=50, 实际=" + top.getScore());
        assertEquals("ENTRY", top.getStage());
        assertEquals("long", top.getSide());
        assertNotNull(top.getScoreBreakdown());
    }

    @Test
    void sortedDescending() {
        MarketFeature mid = MarketFeature.builder()
                .symbol("MIDUSDT").currentPrice(1.0).oiAvailable(true)
                .oiChangeH6(10).oiChangeD30(-10).oiChangeM5(0)
                .priceChangeH6(2).priceChangeH24(5)
                .volChangeH6(2.0).fundingRate(0.0003).fundingHealthy(true)
                .build();
        when(marketFeatureService.computeFeatures(anyList()))
                .thenReturn(Map.of("AAAUSDT", strong("AAAUSDT"), "MIDUSDT", mid));

        AnalysisReport r = analyzer.analyze(List.of("AAAUSDT", "MIDUSDT"));
        assertEquals(2, r.getCoins().size());
        assertTrue(r.getCoins().get(0).getScore() >= r.getCoins().get(1).getScore(), "应按综合分降序");
    }
}
