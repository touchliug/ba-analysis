package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FirstYinDayAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;

    @Mock
    private AppProperties appProperties;

    private FirstYinDayAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        AppProperties.AnalysisConfig analysisConfig = new AppProperties.AnalysisConfig();
        AppProperties.FirstYinDayConfig config = new AppProperties.FirstYinDayConfig();
        config.setDays(3);
        config.setMinTotalRisePercent(15);
        analysisConfig.setFirstYinDay(config);
        when(appProperties.getAnalysis()).thenReturn(analysisConfig);
        AppProperties.ConcurrencyConfig concurrencyConfig = new AppProperties.ConcurrencyConfig();
        when(appProperties.getConcurrency()).thenReturn(concurrencyConfig);
        analyzer = new FirstYinDayAnalyzer(dataFetchService, appProperties);
    }

    @Test
    void analyze_shouldMatchWhenConditionsMet() {
        List<KlineData> klines = List.of(
                createKline(100, 108),
                createKline(108, 115),
                createKline(115, 118),
                createKline(118, 114)
        );

        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", klines));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(1, report.getMatchedCount());
        assertEquals("首阴日", report.getAnalysisType());
        assertTrue(report.getCoins().get(0).getDetail().contains("今日首阴"));
        assertTrue(report.getCoins().get(0).getDetail().contains("昨收"));
    }

    @Test
    void analyze_shouldRejectWhenTotalRiseTooLow() {
        List<KlineData> klines = List.of(
                createKline(100, 103),
                createKline(103, 105),
                createKline(105, 107),
                createKline(107, 106)
        );

        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", klines));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void analyze_shouldRejectWhenCloseNotBelowYesterday() {
        List<KlineData> klines = List.of(
                createKline(100, 108),
                createKline(108, 115),
                createKline(115, 118),
                createKline(119, 118.3)
        );

        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", klines));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(0, report.getMatchedCount());
    }

    private KlineData createKline(double open, double close) {
        KlineData k = new KlineData();
        k.setOpen(String.valueOf(open));
        k.setClose(String.valueOf(close));
        k.setHigh(String.valueOf(Math.max(open, close) + 1));
        k.setLow(String.valueOf(Math.min(open, close) - 1));
        k.setVolume("1000");
        k.setQuoteAssetVolume("10000");
        return k;
    }
}
