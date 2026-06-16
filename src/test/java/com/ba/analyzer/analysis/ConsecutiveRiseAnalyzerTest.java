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
class ConsecutiveRiseAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;

    @Mock
    private AppProperties appProperties;

    private ConsecutiveRiseAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        AppProperties.AnalysisConfig analysisConfig = new AppProperties.AnalysisConfig();
        AppProperties.ConsecutiveRiseConfig config = new AppProperties.ConsecutiveRiseConfig();
        config.setDays(3);
        analysisConfig.setConsecutiveRise(config);
        when(appProperties.getAnalysis()).thenReturn(analysisConfig);
        AppProperties.ConcurrencyConfig concurrencyConfig = new AppProperties.ConcurrencyConfig();
        when(appProperties.getConcurrency()).thenReturn(concurrencyConfig);
        analyzer = new ConsecutiveRiseAnalyzer(dataFetchService, appProperties);
    }

    @Test
    void analyze_shouldFindConsecutiveRise() {
        List<KlineData> klines = List.of(
                createKline(100, 105),
                createKline(105, 110),
                createKline(110, 120)
        );
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", klines));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));
        assertEquals(1, report.getMatchedCount());
        assertEquals("BTCUSDT", report.getCoins().get(0).getSymbol());
    }

    @Test
    void analyze_shouldNotMatchWhenOneDayDrops() {
        List<KlineData> klines = List.of(
                createKline(100, 105),
                createKline(110, 105),
                createKline(105, 110)
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
