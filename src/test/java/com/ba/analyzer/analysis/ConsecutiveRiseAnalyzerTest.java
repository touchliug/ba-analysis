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
class ConsecutiveRiseAnalyzerTest {

    private static final long DAY = 86_400_000L;

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
        config.setMinDailyRisePercent(0.5);
        analysisConfig.setConsecutiveRise(config);
        // 首阴日默认: days=3, minTotalRisePercent=15 (用于 [首阴候选] 标记)
        analysisConfig.setFirstYinDay(new AppProperties.FirstYinDayConfig());
        lenient().when(appProperties.getAnalysis()).thenReturn(analysisConfig);
        lenient().when(appProperties.getConcurrency()).thenReturn(new AppProperties.ConcurrencyConfig());
        analyzer = new ConsecutiveRiseAnalyzer(dataFetchService, appProperties);
    }

    /** 已收盘K线序列 (最后一根1分钟前刚收盘)。 */
    private List<KlineData> closed(double[][] openClose) {
        long now = System.currentTimeMillis();
        List<KlineData> list = new ArrayList<>();
        int n = openClose.length;
        for (int i = 0; i < n; i++) {
            long closeTime = now - 60_000 - (long) (n - 1 - i) * DAY;
            list.add(kline(openClose[i][0], openClose[i][1], closeTime - DAY + 1, closeTime));
        }
        return list;
    }

    /** 追加当前未收盘日K (5分钟前开盘)。 */
    private static List<KlineData> withCurrent(List<KlineData> list, double open, double close) {
        long openTime = System.currentTimeMillis() - 5 * 60_000;
        list.add(kline(open, close, openTime, openTime + DAY - 1));
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

    private AnalysisReport run(List<KlineData> klines) {
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt())).thenReturn(Map.of("BTCUSDT", klines));
        return analyzer.analyze(List.of("BTCUSDT"));
    }

    /** 基准阴线(收100) → 3连阳累涨20%。 */
    private static final double[][] RUN3 = {{102, 100}, {100, 105}, {105, 110}, {110, 120}};

    @Test
    void matchesRunAndMarksFirstYinCandidate() {
        AnalysisReport report = run(closed(RUN3));

        assertEquals(1, report.getMatchedCount());
        AnalysisReport.CoinAnalysis c = report.getCoins().get(0);
        assertEquals(120.0, c.getCurrentPrice(), 1e-9);
        assertEquals(20.0, c.getChangePercent(), 1e-9);
        assertTrue(c.getDetail().startsWith("[首阴候选] 连涨3天 累涨20.00%"));
    }

    @Test
    void unclosedGreenCandleDoesNotCountTowardRun() {
        double[][] two = {{102, 100}, {100, 105}, {105, 110}};
        assertEquals(0, run(withCurrent(closed(two), 110, 115)).getMatchedCount());
    }

    @Test
    void unclosedRedCandleDoesNotBreakRun() {
        assertEquals(1, run(withCurrent(closed(RUN3), 120, 118)).getMatchedCount());
    }

    @Test
    void rejectsMicroRiseCandles() {
        double[][] micro = {{1.002, 1.0}, {1.0, 1.001}, {1.001, 1.002}, {1.002, 1.003}};
        assertEquals(0, run(closed(micro)).getMatchedCount());
    }

    @Test
    void reportsActualRunLongerThanMinDays() {
        double[][] run5 = {{102, 100}, {100, 104}, {104, 108}, {108, 112}, {112, 116}, {116, 120}};
        AnalysisReport report = run(closed(run5));

        assertEquals(1, report.getMatchedCount());
        assertTrue(report.getCoins().get(0).getDetail().contains("连涨5天"));
    }

    @Test
    void weakRunIsNotFirstYinCandidate() {
        double[][] weak = {{102, 100}, {100, 102}, {102, 104}, {104, 106}};
        AnalysisReport report = run(closed(weak));

        assertEquals(1, report.getMatchedCount());
        assertFalse(report.getCoins().get(0).getDetail().contains("首阴候选"));
    }

    @Test
    void minTotalRiseFilterViaRequestParam() {
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", closed(RUN3)));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"), Map.of("minTotalRisePercent", 25.0));

        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void skipsStaleData() {
        List<KlineData> klines = closed(RUN3);
        klines.forEach(k -> {
            k.setOpenTime(k.getOpenTime() - 3 * DAY);
            k.setCloseTime(k.getCloseTime() - 3 * DAY);
        });
        assertEquals(0, run(klines).getMatchedCount());
    }

    @Test
    void marksRunReachingWindowStart() {
        double[][] allGreen = {{100, 105}, {105, 110}, {110, 120}};
        AnalysisReport report = run(closed(allGreen));

        assertEquals(1, report.getMatchedCount());
        String detail = report.getCoins().get(0).getDetail();
        assertTrue(detail.contains("连涨≥3天"));
        assertTrue(detail.contains("量比-(无历史)"));
    }

    @Test
    void maxDailyRiseActsAsSlowRiseFilter() {
        // 3连阳单日 +5%/+4.8%/+9.1%: 设单日上限3%即原"缓涨币", 均超限不算有效阳线
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", closed(RUN3)));
        assertEquals(0, analyzer.analyze(List.of("BTCUSDT"), Map.of("maxDailyRisePercent", 3.0)).getMatchedCount());

        double[][] gentle = {{102, 100}, {100, 102}, {102, 104}, {104, 106}};
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", closed(gentle)));
        assertEquals(1, analyzer.analyze(List.of("BTCUSDT"), Map.of("maxDailyRisePercent", 3.0)).getMatchedCount());
    }

    @Test
    void maxVolumeRatioRequiresShrinkingVolume() {
        // 原"缓涨币"的缩量过滤方向写反且参数需>1.0才生效, 缩量条件根本设不出来
        // (KlineData 首次读取后缓存解析值, 每种量能用一份新序列)
        List<KlineData> shrinking = closed(RUN3);
        shrinking.get(0).setQuoteAssetVolume("20000");
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt())).thenReturn(Map.of("BTCUSDT", shrinking));
        assertEquals(1, analyzer.analyze(List.of("BTCUSDT"), Map.of("maxVolumeRatio", 0.8)).getMatchedCount());

        List<KlineData> expanding = closed(RUN3);
        expanding.get(0).setQuoteAssetVolume("5000");
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt())).thenReturn(Map.of("BTCUSDT", expanding));
        assertEquals(0, analyzer.analyze(List.of("BTCUSDT"), Map.of("maxVolumeRatio", 0.8)).getMatchedCount());
    }

    @Test
    void volumeFilterRejectsWhenNoHistory() {
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", closed(new double[][]{{100, 105}, {105, 110}, {110, 120}})));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"), Map.of("volumeSurgeRatio", 1.5));

        assertEquals(0, report.getMatchedCount());
    }
}
