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

/**
 * 连跌今涨分析器单元测试
 * 测试DropThenRiseAnalyzer的核心逻辑：
 * - 前N天连续下跌但今天上涨的币应被识别（反弹信号）
 */
@ExtendWith(MockitoExtension.class)
class DropThenRiseAnalyzerTest {

    /** 模拟的数据获取服务 */
    @Mock
    private DataFetchService dataFetchService;

    /** 模拟的配置属性 */
    @Mock
    private AppProperties appProperties;

    /** 被测试的分析器实例 */
    private DropThenRiseAnalyzer analyzer;

    /** 测试初始化：配置前5天连续下跌 */
    @BeforeEach
    void setUp() {
        AppProperties.AnalysisConfig analysisConfig = new AppProperties.AnalysisConfig();
        AppProperties.DropThenRiseConfig config = new AppProperties.DropThenRiseConfig();
        config.setDays(5);
        analysisConfig.setDropThenRise(config);
        when(appProperties.getAnalysis()).thenReturn(analysisConfig);
        AppProperties.ConcurrencyConfig concurrencyConfig = new AppProperties.ConcurrencyConfig();
        when(appProperties.getConcurrency()).thenReturn(concurrencyConfig);

        analyzer = new DropThenRiseAnalyzer(dataFetchService, appProperties);
    }

    /** 测试：前5天连续下跌(120→115→110→105→100→95)，今天上涨(95→102)，应匹配1个 */
    @Test
    void analyze_shouldFindDropThenRise() {
        List<KlineData> klines = List.of(
                createKline(120, 115),
                createKline(115, 110),
                createKline(110, 105),
                createKline(105, 100),
                createKline(100, 95),
                createKline(95, 102)
        );
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", klines));

        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));

        assertEquals(1, report.getMatchedCount());
    }

    /** 构建测试用K线数据的辅助方法 */
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
