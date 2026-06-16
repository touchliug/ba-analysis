package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
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
class OiConsecutiveRiseAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;

    @Mock
    private AppProperties appProperties;

    private OiConsecutiveRiseAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        AppProperties.AnalysisConfig analysisConfig = new AppProperties.AnalysisConfig();
        AppProperties.OiConsecutiveRiseConfig config = new AppProperties.OiConsecutiveRiseConfig();
        config.setDays(3);
        config.setMaxPriceRisePercent(7);
        analysisConfig.setOiConsecutiveRise(config);
        when(appProperties.getAnalysis()).thenReturn(analysisConfig);
        AppProperties.ConcurrencyConfig concurrencyConfig = new AppProperties.ConcurrencyConfig();
        when(appProperties.getConcurrency()).thenReturn(concurrencyConfig);
        analyzer = new OiConsecutiveRiseAnalyzer(dataFetchService, appProperties);
    }

    @Test
    void analyze_shouldMatchWhenOiRisesAndPriceWithinLimit() {
        List<KlineData> klines = List.of(
                createKline(100, 102),
                createKline(102, 103),
                createKline(103, 106),
                createKline(106, 107)
        );

        OpenInterestData oi1 = new OpenInterestData();
        oi1.setOpenInterest("10000");
        OpenInterestData oi2 = new OpenInterestData();
        oi2.setOpenInterest("10500");
        OpenInterestData oi3 = new OpenInterestData();
        oi3.setOpenInterest("11000");
        OpenInterestData oi4 = new OpenInterestData();
        oi4.setOpenInterest("12000");

        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", klines));
        when(dataFetchService.fetchOpenInterestHistoryBatch(anyList(), eq(4)))
                .thenReturn(Map.of("BTCUSDT", List.of(oi1, oi2, oi3, oi4)));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(1, report.getMatchedCount());
        assertEquals("持仓量连增", report.getAnalysisType());
        assertTrue(report.getCoins().get(0).getDetail().contains("持仓量连续3天上涨"));
        assertTrue(report.getCoins().get(0).getDetail().contains("价格涨幅"));
    }

    @Test
    void analyze_shouldRejectWhenOiDecreases() {
        List<KlineData> klines = List.of(
                createKline(100, 102),
                createKline(102, 103),
                createKline(103, 106),
                createKline(106, 107)
        );

        OpenInterestData oi1 = new OpenInterestData();
        oi1.setOpenInterest("10000");
        OpenInterestData oi2 = new OpenInterestData();
        oi2.setOpenInterest("10500");
        OpenInterestData oi3 = new OpenInterestData();
        oi3.setOpenInterest("9000");  // dropped
        OpenInterestData oi4 = new OpenInterestData();
        oi4.setOpenInterest("11000");

        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", klines));
        when(dataFetchService.fetchOpenInterestHistoryBatch(anyList(), eq(4)))
                .thenReturn(Map.of("BTCUSDT", List.of(oi1, oi2, oi3, oi4)));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void analyze_shouldRejectWhenPriceExceedsLimit() {
        List<KlineData> klines = List.of(
                createKline(100, 102),
                createKline(102, 104),
                createKline(104, 106),
                createKline(106, 116)
        );

        OpenInterestData oi1 = new OpenInterestData();
        oi1.setOpenInterest("10000");
        OpenInterestData oi2 = new OpenInterestData();
        oi2.setOpenInterest("10500");
        OpenInterestData oi3 = new OpenInterestData();
        oi3.setOpenInterest("11000");
        OpenInterestData oi4 = new OpenInterestData();
        oi4.setOpenInterest("12000");

        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", klines));
        when(dataFetchService.fetchOpenInterestHistoryBatch(anyList(), eq(4)))
                .thenReturn(Map.of("BTCUSDT", List.of(oi1, oi2, oi3, oi4)));

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
