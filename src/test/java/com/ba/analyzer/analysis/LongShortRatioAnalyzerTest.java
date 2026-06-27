package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.service.DataFetchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LongShortRatioAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;
    @Mock
    private AppProperties appProperties;

    private LongShortRatioAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        AppProperties.AnalysisConfig analysisConfig = new AppProperties.AnalysisConfig();
        analysisConfig.setLongShortRatio(new AppProperties.LongShortRatioConfig()); // defaults
        when(appProperties.getAnalysis()).thenReturn(analysisConfig);
        analyzer = new LongShortRatioAnalyzer(dataFetchService, appProperties);
    }

    private List<LongShortRatioData> series(double... ratios) {
        List<LongShortRatioData> list = new ArrayList<>();
        for (double r : ratios) {
            LongShortRatioData d = new LongShortRatioData();
            d.setLongShortRatio(String.valueOf(r));
            list.add(d); // time-ascending; last = latest
        }
        return list;
    }

    /** TOP_POSITION rising 1.0->1.2 (+20%), GLOBAL_ACCOUNT falling 2.0->1.6, TAKER 1.5 -> strong signal. */
    private void stubSmartMoney(String symbol) {
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TOP_POSITION"), eq("1h"), anyInt()))
                .thenReturn(Map.of(symbol, series(1.0, 1.05, 1.1, 1.15, 1.18, 1.2,
                        1.2, 1.2, 1.2, 1.2, 1.2, 1.2, 1.2)));
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("GLOBAL_ACCOUNT"), eq("1h"), anyInt()))
                .thenReturn(Map.of(symbol, series(2.0, 1.95, 1.9, 1.85, 1.8, 1.75,
                        1.72, 1.7, 1.68, 1.66, 1.64, 1.62, 1.6)));
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TAKER"), eq("1h"), anyInt()))
                .thenReturn(Map.of(symbol, series(1.5)));
    }

    @Test
    void analyze_matchesSmartMoneyAccumulation() {
        stubSmartMoney("BTCUSDT");
        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));
        assertEquals("多空比异动", report.getAnalysisType());
        assertEquals(1, report.getMatchedCount());
        AnalysisReport.CoinAnalysis coin = report.getCoins().get(0);
        assertTrue(coin.getScore() >= 60, "smart-money sample should clear minScore");
        assertEquals("long", coin.getSide());
    }

    @Test
    void analyze_skipsWhenTopPositionSeriesTooShort() {
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TOP_POSITION"), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series(1.0, 1.1, 1.2)));
        lenient().when(dataFetchService.getLongShortRatioBatch(anyList(), eq("GLOBAL_ACCOUNT"), eq("1h"), anyInt()))
                .thenReturn(Map.of());
        lenient().when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TAKER"), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series(1.5)));
        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));
        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void analyze_skipsWhenTakerMissing() {
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TOP_POSITION"), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series(1.0, 1.05, 1.1, 1.15, 1.18, 1.2,
                        1.2, 1.2, 1.2, 1.2, 1.2, 1.2, 1.2)));
        lenient().when(dataFetchService.getLongShortRatioBatch(anyList(), eq("GLOBAL_ACCOUNT"), eq("1h"), anyInt()))
                .thenReturn(Map.of());
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TAKER"), eq("1h"), anyInt()))
                .thenReturn(Map.of()); // missing
        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));
        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void analyze_flatTopPositionAndWeakTakerBelowMinScore() {
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TOP_POSITION"), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series(1.0, 1.0, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)));
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("GLOBAL_ACCOUNT"), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series(1.0, 1.0, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)));
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TAKER"), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series(1.0)));
        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));
        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void isEnabledReadsConfig() {
        assertTrue(analyzer.isEnabled());
        assertTrue(analyzer.requiresIntradayData());
        assertEquals("多空比异动", analyzer.getName());
    }
}
