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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceFallStreakAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;
    @Mock
    private AppProperties appProperties;

    private PriceFallStreakAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        AppProperties.AnalysisConfig config = new AppProperties.AnalysisConfig();
        config.setPriceFallStreak(new AppProperties.StreakConfig());
        when(appProperties.getAnalysis()).thenReturn(config);
        analyzer = new PriceFallStreakAnalyzer(dataFetchService, appProperties);
    }

    @Test
    void matchesFiveConsecutiveBearishCandles() {
        when(dataFetchService.fetchKlines(anyList(), eq("1d"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", candles(5, false)));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(1, report.getMatchedCount());
        assertTrue(report.getCoins().get(0).getDetail().contains("连阴≥5"));
        assertTrue(report.getCoins().get(0).getChangePercent() < 0);
    }

    @Test
    void fourBearishCandlesAreNotEnough() {
        when(dataFetchService.fetchKlines(anyList(), eq("1d"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", candles(4, false)));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(0, report.getMatchedCount());
    }

    private static final long DAY = 86_400_000L;

    private static List<KlineData> candles(int count, boolean bullish) {
        long start = System.currentTimeMillis() - count * DAY - 1000;
        List<KlineData> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(candle(bullish ? 100 : 101, bullish ? 101 : 100, start + i * DAY));
        }
        return list;
    }

    private static KlineData candle(double open, double close, long openTime) {
        KlineData k = new KlineData();
        k.setOpenTime(openTime);
        k.setCloseTime(openTime + DAY - 1);
        k.setOpen(String.valueOf(open));
        k.setClose(String.valueOf(close));
        k.setHigh(String.valueOf(Math.max(open, close) + 1));
        k.setLow(String.valueOf(Math.min(open, close) - 1));
        k.setQuoteAssetVolume("1000");
        return k;
    }
}
