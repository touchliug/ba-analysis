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

/**
 * 价涨仓增分析器单元测试
 * 测试PriceDropOiRiseAnalyzer的核心逻辑：
 * - 最近N天价格上涨不超过阈值但持仓量显著增长的币应被识别
 * - 同时验证持仓量数据的获取和展示
 */
@ExtendWith(MockitoExtension.class)
class PriceDropOiRiseAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;

    @Mock
    private AppProperties appProperties;

    private PriceDropOiRiseAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        AppProperties.AnalysisConfig analysisConfig = new AppProperties.AnalysisConfig();
        AppProperties.PriceDropOiRiseConfig config = new AppProperties.PriceDropOiRiseConfig();
        config.setDays(2);
        config.setMaxPriceRisePercent(10);
        config.setMinOiRisePercent(20);
        analysisConfig.setPriceDropOiRise(config);
        when(appProperties.getAnalysis()).thenReturn(analysisConfig);
        AppProperties.ConcurrencyConfig concurrencyConfig = new AppProperties.ConcurrencyConfig();
        when(appProperties.getConcurrency()).thenReturn(concurrencyConfig);
        analyzer = new PriceDropOiRiseAnalyzer(dataFetchService, appProperties);
    }

    @Test
    void analyze_shouldFindPriceRiseOiRise() {
        List<KlineData> klines = List.of(
                createKline(100, 102),
                createKline(102, 105),
                createKline(105, 108)
        );

        OpenInterestData oi1 = new OpenInterestData();
        oi1.setSumOpenInterest("10000");

        OpenInterestData oi2 = new OpenInterestData();
        oi2.setSumOpenInterest("11000");

        OpenInterestData oi3 = new OpenInterestData();
        oi3.setSumOpenInterest("12500");

        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", klines));
        when(dataFetchService.fetchOpenInterestHistoryBatch(anyList(), eq(2)))
                .thenReturn(Map.of("BTCUSDT", List.of(oi1, oi2, oi3)));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(1, report.getMatchedCount());
        assertEquals("价涨仓增", report.getAnalysisType());
        assertTrue(report.getDescription().contains("价格上涨不超过10%"));
        assertTrue(report.getDescription().contains("持仓量增长超过20%"));
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