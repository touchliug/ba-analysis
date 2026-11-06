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
class PriceRiseStreakAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;
    @Mock
    private AppProperties appProperties;

    private AppProperties.AnalysisConfig analysisConfig;
    private PriceRiseStreakAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analysisConfig = new AppProperties.AnalysisConfig();
        analysisConfig.setPriceRiseStreak(new AppProperties.StreakConfig());
        when(appProperties.getAnalysis()).thenReturn(analysisConfig);
        analyzer = new PriceRiseStreakAnalyzer(dataFetchService, appProperties);
    }

    @Test
    void matchesFiveConsecutiveBullishCandles() {
        when(dataFetchService.fetchKlines(anyList(), eq("1d"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", candles(5, true)));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(1, report.getMatchedCount());
        assertTrue(report.getCoins().get(0).getDetail().contains("连阳≥5"));
    }

    @Test
    void rejectsWhenTheLastCandleIsBearish() {
        List<KlineData> ks = candles(5, true);
        KlineData last = ks.get(4);
        last.setOpen("101");
        last.setClose("100");
        when(dataFetchService.fetchKlines(anyList(), eq("1d"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", ks));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void unclosedCandleDoesNotCountTowardTheStreak() {
        List<KlineData> ks = candles(4, true);
        ks.add(candle(100, 101, System.currentTimeMillis(), DAY)); // closeTime is in the future
        when(dataFetchService.fetchKlines(anyList(), eq("1d"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", ks));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void paramSpecOffersDayAndHour() {
        @SuppressWarnings("unchecked")
        Map<String, Object> interval = (Map<String, Object>) analyzer.getParamSpec().get("interval");

        assertEquals("select", interval.get("type"));
        assertEquals(List.of("1d", "1h"), interval.get("options"));
    }

    private static final long DAY = 86_400_000L;

    private static List<KlineData> candles(int count, boolean bullish) {
        long start = System.currentTimeMillis() - count * DAY - 1000;
        List<KlineData> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(candle(100, bullish ? 101 : 99, start + i * DAY, DAY));
        }
        return list;
    }

    private static KlineData candle(double open, double close, long openTime, long span) {
        KlineData k = new KlineData();
        k.setOpenTime(openTime);
        k.setCloseTime(openTime + span - 1);
        k.setOpen(String.valueOf(open));
        k.setClose(String.valueOf(close));
        k.setHigh(String.valueOf(Math.max(open, close) + 1));
        k.setLow(String.valueOf(Math.min(open, close) - 1));
        k.setQuoteAssetVolume("1000");
        return k;
    }
}
