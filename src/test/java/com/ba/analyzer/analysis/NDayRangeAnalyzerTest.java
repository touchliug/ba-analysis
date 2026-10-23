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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NDayRangeAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;

    @Mock
    private AppProperties appProperties;

    private NDayRangeAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        AppProperties.AnalysisConfig ac = new AppProperties.AnalysisConfig();
        AppProperties.NDayRangeConfig cfg = new AppProperties.NDayRangeConfig();
        cfg.setDays(3);
        ac.setNDayRange(cfg);
        lenient().when(appProperties.getAnalysis()).thenReturn(ac);
        analyzer = new NDayRangeAnalyzer(dataFetchService, appProperties);
    }

    private static KlineData k(double high, double low, double close) {
        KlineData kd = new KlineData();
        kd.setOpen(String.valueOf(close));
        kd.setHigh(String.valueOf(high));
        kd.setLow(String.valueOf(low));
        kd.setClose(String.valueOf(close));
        kd.setQuoteAssetVolume("1000");
        return kd;
    }

    private AnalysisReport run(Map<String, List<KlineData>> data, Map<String, Object> params) {
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt())).thenReturn(data);
        return analyzer.analyze(new ArrayList<>(data.keySet()), params);
    }

    @Test
    void roundLevelUsesPriceMagnitude() {
        assertTrue(NDayRangeAnalyzer.isRoundLevel(60_050));   // 贴近 60000
        assertFalse(NDayRangeAnalyzer.isRoundLevel(61_234));  // 原实现对≥1000的价格永远为真
        assertTrue(NDayRangeAnalyzer.isRoundLevel(0.3004));   // 贴近 0.3
        assertFalse(NDayRangeAnalyzer.isRoundLevel(0.347));
        assertTrue(NDayRangeAnalyzer.isRoundLevel(0.9995));   // 贴近 1
        assertTrue(NDayRangeAnalyzer.isRoundLevel(2.0));
        assertFalse(NDayRangeAnalyzer.isRoundLevel(2.5));
        assertFalse(NDayRangeAnalyzer.isRoundLevel(0));
    }

    @Test
    void leopardNumber() {
        assertTrue(NDayRangeAnalyzer.isLeopardNumber(NDayRangeAnalyzer.formatPrice(0.1111)));
        assertTrue(NDayRangeAnalyzer.isLeopardNumber(NDayRangeAnalyzer.formatPrice(0.0333333)));
        assertFalse(NDayRangeAnalyzer.isLeopardNumber(NDayRangeAnalyzer.formatPrice(0.000111)));
        assertFalse(NDayRangeAnalyzer.isLeopardNumber(NDayRangeAnalyzer.formatPrice(0.1234)));
    }

    @Test
    void computesPositionAndFiltersByRange() {
        Map<String, List<KlineData>> data = new LinkedHashMap<>();
        // 区间 100-200, 现价 110 → 位置 10%
        data.put("LOWUSDT", List.of(k(200, 150, 180), k(160, 100, 120), k(130, 105, 110)));
        // 区间 100-200, 现价 190 → 位置 90%
        data.put("HIGHUSDT", List.of(k(150, 100, 120), k(180, 130, 170), k(200, 160, 190)));

        AnalysisReport report = run(data, Map.of("maxPositionPercent", 20.0));

        assertEquals(1, report.getMatchedCount());
        AnalysisReport.CoinAnalysis c = report.getCoins().get(0);
        assertEquals("LOWUSDT", c.getSymbol());
        assertEquals(10.0, c.getChangePercent(), 1e-9);
        assertTrue(c.getDetail().contains("3日最低100(") , c.getDetail());
    }

    @Test
    void onlyTaggedAndTaggedFirst() {
        Map<String, List<KlineData>> data = new LinkedHashMap<>();
        data.put("PLAINUSDT", List.of(k(1.7, 1.3, 1.4)));
        data.put("ROUNDUSDT", List.of(k(2.5, 2.0, 2.2)));   // 最低 2.0 = 整数关口

        AnalysisReport all = run(data, Map.of());
        assertEquals("ROUNDUSDT", all.getCoins().get(0).getSymbol());
        assertTrue(all.getCoins().get(0).getDetail().startsWith("[整数关口]"));

        AnalysisReport tagged = run(data, Map.of("onlyTagged", true));
        assertEquals(1, tagged.getMatchedCount());
        assertEquals("ROUNDUSDT", tagged.getCoins().get(0).getSymbol());
    }

    @Test
    void paramSpecDefaultsComeFromConfig() {
        Map<String, Object> spec = analyzer.getParamSpec();
        assertEquals(List.of("days", "minPositionPercent", "maxPositionPercent", "onlyTagged"),
                new ArrayList<>(spec.keySet()));
        assertEquals(3, ((Map<?, ?>) spec.get("days")).get("default"));
    }
}
