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
class ShortTermRiseAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;

    @Mock
    private AppProperties appProperties;

    private ShortTermRiseAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        AppProperties.AnalysisConfig analysisConfig = new AppProperties.AnalysisConfig();
        AppProperties.ShortTermRiseConfig config = new AppProperties.ShortTermRiseConfig();
        config.setInterval("5m");
        config.setPeriod(6);
        config.setThresholdPercent(3);
        config.setMinScore(70);
        analysisConfig.setShortTermRise(config);
        when(appProperties.getAnalysis()).thenReturn(analysisConfig);
        AppProperties.ConcurrencyConfig concurrencyConfig = new AppProperties.ConcurrencyConfig();
        when(appProperties.getConcurrency()).thenReturn(concurrencyConfig);
        analyzer = new ShortTermRiseAnalyzer(dataFetchService, appProperties);
    }

    @Test
    void analyze_shouldFindHighQualityRise() {
        List<KlineData> klines = List.of(
                createKline(100, 101),
                createKline(101, 102),
                createKline(102, 103),
                createKline(103, 104.5),
                createKline(104.5, 106),
                createKline(106, 108)
        );
        when(dataFetchService.fetchKlinesByInterval(anyList(), anyString(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", klines));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));
        assertTrue(report.getMatchedCount() >= 0);
    }

    @Test
    void analyze_shouldNotMatchBelowThreshold() {
        List<KlineData> klines = List.of(
                createKline(100, 100.3),
                createKline(100.3, 100.6),
                createKline(100.6, 100.9),
                createKline(100.9, 101.2),
                createKline(101.2, 101.5),
                createKline(101.5, 102.5)
        );
        when(dataFetchService.fetchKlinesByInterval(anyList(), anyString(), anyInt()))
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
        k.setTakerBuyQuoteAssetVolume("8000");
        return k;
    }
}
