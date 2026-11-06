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
class OiRiseStreakAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;
    @Mock
    private AppProperties appProperties;

    private AppProperties.StreakConfig config;
    private OiRiseStreakAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        config = new AppProperties.StreakConfig();
        AppProperties.AnalysisConfig analysisConfig = new AppProperties.AnalysisConfig();
        analysisConfig.setOiRiseStreak(config);
        when(appProperties.getAnalysis()).thenReturn(analysisConfig);
        analyzer = new OiRiseStreakAnalyzer(dataFetchService, appProperties);
    }

    @Test
    void matchesFiveConsecutiveOiIncreases() {
        when(dataFetchService.fetchOpenInterestHistoryBatch(anyList(), eq("1d"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", oiSeries(6, true)));
        when(dataFetchService.fetchKlines(anyList(), eq("1d"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", priceSeries()));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(1, report.getMatchedCount());
        assertTrue(report.getCoins().get(0).getDetail().contains("OI连涨5"));
    }

    @Test
    void flatOiDoesNotMatch() {
        when(dataFetchService.fetchOpenInterestHistoryBatch(anyList(), eq("1d"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", oiSeries(6, false)));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void insufficientOiPointsAreSkipped() {
        when(dataFetchService.fetchOpenInterestHistoryBatch(anyList(), eq("1d"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", oiSeries(5, true)));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void acceptsDailyOiFromYesterdayBecauseSyncRunsAfterDailyAnalysis() {
        List<OpenInterestData> series = oiSeries(6, true);
        long shift = 25 * HOUR;
        series.forEach(d -> d.setTimestamp(d.getTimestamp() - shift));
        when(dataFetchService.fetchOpenInterestHistoryBatch(anyList(), eq("1d"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series));
        when(dataFetchService.fetchKlines(anyList(), eq("1d"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", priceSeries()));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(1, report.getMatchedCount());
    }

    @Test
    void readsHourlyOiWhenConfiguredForHourly() {
        config.setInterval("1h");
        when(dataFetchService.fetchOpenInterestHistoryBatch(anyList(), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", oiSeries(6, true)));
        when(dataFetchService.fetchKlines(anyList(), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", priceSeries()));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(1, report.getMatchedCount());
        assertTrue(report.getCoins().get(0).getDetail().contains("1h"));
    }

    private static final long HOUR = 3_600_000L;

    private static List<OpenInterestData> oiSeries(int count, boolean rising) {
        List<OpenInterestData> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            OpenInterestData d = new OpenInterestData();
            d.setSymbol("BTCUSDT");
            d.setTimestamp(now - (count - 1 - i) * HOUR);
            d.setSumOpenInterest(String.valueOf(rising ? 100 + i : 100));
            list.add(d);
        }
        return list;
    }

    private static List<KlineData> priceSeries() {
        KlineData k = new KlineData();
        k.setOpenTime(System.currentTimeMillis() - HOUR);
        k.setCloseTime(System.currentTimeMillis() + HOUR);
        k.setClose("100");
        return List.of(k);
    }
}
