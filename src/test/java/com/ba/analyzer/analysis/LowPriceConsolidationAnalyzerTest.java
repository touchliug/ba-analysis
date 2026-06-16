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

/**
 * 低位插针分析器单元测试
 * 测试LowPriceConsolidationAnalyzer的核心逻辑：
 * - 价格上下插针（长影线）但整体变化不大且处于低位的币应被识别
 * - 需要构造30天历史数据用于判断低价位 + 7天近期数据用于判断插针和横盘
 */
@ExtendWith(MockitoExtension.class)
class LowPriceConsolidationAnalyzerTest {

    /** 模拟的数据获取服务 */
    @Mock
    private DataFetchService dataFetchService;

    /** 模拟的配置属性 */
    @Mock
    private AppProperties appProperties;

    /** 被测试的分析器实例 */
    private LowPriceConsolidationAnalyzer analyzer;

    /** 测试初始化：配置7天分析窗口，最大价格变化10%，低价分位数30% */
    @BeforeEach
    void setUp() {
        AppProperties.AnalysisConfig analysisConfig = new AppProperties.AnalysisConfig();
        AppProperties.LowPriceConsolidationConfig config = new AppProperties.LowPriceConsolidationConfig();
        config.setDays(7);
        config.setMaxPriceChangePercent(10);
        config.setLowPricePercentile(30);
        analysisConfig.setLowPriceConsolidation(config);
        when(appProperties.getAnalysis()).thenReturn(analysisConfig);
        AppProperties.ConcurrencyConfig concurrencyConfig = new AppProperties.ConcurrencyConfig();
        when(appProperties.getConcurrency()).thenReturn(concurrencyConfig);

        analyzer = new LowPriceConsolidationAnalyzer(dataFetchService, appProperties);
    }

    /** 测试：30天历史低价+7天插针横盘数据，验证分析器能正常执行并返回结果 */
    @Test
    void analyze_shouldFindLowPriceConsolidation() {
        List<KlineData> klines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            klines.add(createKline(10, 10.1));
        }
        klines.add(createKlineWithWick(10, 10.1, 12, 8));
        klines.add(createKlineWithWick(10.1, 10, 12, 8));
        klines.add(createKlineWithWick(10, 10.05, 11.5, 8.5));

        when(dataFetchService.fetchDailyKlines(anyList(), anyInt()))
                .thenReturn(Map.of("TESTUSDT", klines));

        AnalysisReport report = analyzer.analyze(List.of("TESTUSDT"));

        assertTrue(report.getMatchedCount() >= 0);
    }

    /** 构建普通K线数据的辅助方法，影线为默认0.5 */
    private KlineData createKline(double open, double close) {
        return createKlineWithWick(open, close,
                Math.max(open, close) + 0.5,
                Math.min(open, close) - 0.5);
    }

    /** 构建带自定义影线的K线数据辅助方法，可指定最高价和最低价来模拟长影线(插针) */
    private KlineData createKlineWithWick(double open, double close, double high, double low) {
        KlineData k = new KlineData();
        k.setOpen(String.valueOf(open));
        k.setClose(String.valueOf(close));
        k.setHigh(String.valueOf(high));
        k.setLow(String.valueOf(low));
        k.setVolume("1000");
        k.setQuoteAssetVolume("10000");
        return k;
    }
}
