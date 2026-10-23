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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FirstYinDayAnalyzerTest {

    private static final long DAY = 86_400_000L;

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
        lenient().when(appProperties.getAnalysis()).thenReturn(analysisConfig);
        lenient().when(appProperties.getConcurrency()).thenReturn(new AppProperties.ConcurrencyConfig());
        analyzer = new FirstYinDayAnalyzer(dataFetchService, appProperties);
    }

    /** 已收盘K线序列 (最后一根为首阴, 1分钟前刚收盘), 可选追加当前未收盘根。 */
    private List<KlineData> series(double[][] closedOpenClose, Double currentOpen) {
        long now = System.currentTimeMillis();
        List<KlineData> list = new ArrayList<>();
        int n = closedOpenClose.length;
        for (int i = 0; i < n; i++) {
            long closeTime = now - 60_000 - (long) (n - 1 - i) * DAY;
            list.add(kline(closedOpenClose[i][0], closedOpenClose[i][1], closeTime - DAY + 1, closeTime));
        }
        if (currentOpen != null) {
            long openTime = now - 5 * 60_000;
            list.add(kline(currentOpen, currentOpen, openTime, openTime + DAY - 1));
        }
        return list;
    }

    private static KlineData kline(double open, double close, long openTime, long closeTime) {
        KlineData k = new KlineData();
        k.setOpenTime(openTime);
        k.setCloseTime(closeTime);
        k.setOpen(String.valueOf(open));
        k.setClose(String.valueOf(close));
        k.setHigh(String.valueOf(Math.max(open, close) + 1));
        k.setLow(String.valueOf(Math.min(open, close) - 1));
        k.setVolume("1000");
        k.setQuoteAssetVolume("10000");
        return k;
    }

    /** 基准阴线(收100) → 3连阳累涨20% → 首阴跌5%。 */
    private static final double[][] STANDARD = {
            {102, 100}, {100, 108}, {108, 115}, {115, 120}, {120, 114}};

    private AnalysisReport run(List<KlineData> klines) {
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt())).thenReturn(Map.of("BTCUSDT", klines));
        return analyzer.analyze(List.of("BTCUSDT"));
    }

    @Test
    void matchesShortSignalWithNextDayOpenEntry() {
        AnalysisReport report = run(series(STANDARD, 113.5));

        assertEquals(1, report.getMatchedCount());
        AnalysisReport.CoinAnalysis c = report.getCoins().get(0);
        assertEquals("short", c.getSide());
        assertEquals(113.5, c.getCurrentPrice(), 1e-9);
        assertEquals(-5.0, c.getChangePercent(), 1e-9);
        assertNotNull(c.getScore());
        assertTrue(c.getDetail().contains("连涨3天累涨20.0%"));
        assertTrue(c.getDetail().contains("做空 入场113.5000(次日开盘)"));
        // 止损 = 冲高最高价121 × 1.01
        assertTrue(c.getDetail().contains("止损122.2100"));
    }

    @Test
    void ignoresUnclosedCandleAsYin() {
        // 最后一根已收盘的是阳线; 正在走的当日K下跌不应被当成首阴
        double[][] closed = {{101, 100}, {100, 108}, {108, 115}, {115, 120}};
        List<KlineData> klines = series(closed, null);
        long now = System.currentTimeMillis();
        klines.add(kline(120, 110, now - 60_000, now + DAY));

        assertEquals(0, run(klines).getMatchedCount());
    }

    @Test
    void fallsBackToYinCloseWhenNoCurrentCandle() {
        AnalysisReport report = run(series(STANDARD, null));

        assertEquals(1, report.getMatchedCount());
        assertTrue(report.getCoins().get(0).getDetail().contains("入场114.0000(首阴收盘)"));
    }

    @Test
    void rejectsShallowYin() {
        double[][] shallow = {{102, 100}, {100, 108}, {108, 115}, {115, 120}, {120, 119}};
        assertEquals(0, run(series(shallow, 119.0)).getMatchedCount());
    }

    @Test
    void rejectsWhenTotalRiseTooLow() {
        double[][] weak = {{102, 100}, {100, 103}, {103, 105}, {105, 107}, {107, 104}};
        assertEquals(0, run(series(weak, 104.0)).getMatchedCount());
    }

    @Test
    void rejectsWhenStopTooFarViaRequestParam() {
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series(STANDARD, 113.5)));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"), Map.of("maxStopLossPct", 3.0));

        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void skipsStaleData() {
        List<KlineData> klines = series(STANDARD, null);
        klines.forEach(k -> {
            k.setOpenTime(k.getOpenTime() - 3 * DAY);
            k.setCloseTime(k.getCloseTime() - 3 * DAY);
        });
        assertEquals(0, run(klines).getMatchedCount());
    }

    @Test
    void countsActualRunLongerThanMinDays() {
        double[][] longRun = {{102, 100}, {100, 104}, {104, 108}, {108, 112}, {112, 116}, {116, 120}, {120, 114}};
        AnalysisReport report = run(series(longRun, 113.5));

        assertEquals(1, report.getMatchedCount());
        assertTrue(report.getCoins().get(0).getDetail().contains("连涨5天累涨20.0%"));
    }

    @Test
    void scoreTopTiers() {
        assertEquals(100, FirstYinDayAnalyzer.scoreTop(65, 11, 0.5, 2.5).total);
        assertEquals(16, FirstYinDayAnalyzer.scoreTop(10, 1, 0, 0.5).total);
    }
}
