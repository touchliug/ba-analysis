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
class LowPriceConsolidationAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;

    @Mock
    private AppProperties appProperties;

    private LowPriceConsolidationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        AppProperties.AnalysisConfig ac = new AppProperties.AnalysisConfig();
        AppProperties.LowPriceConsolidationConfig cfg = new AppProperties.LowPriceConsolidationConfig();
        cfg.setDays(5);
        cfg.setLowLookbackDays(40);
        ac.setLowPriceConsolidation(cfg);
        lenient().when(appProperties.getAnalysis()).thenReturn(ac);
        analyzer = new LowPriceConsolidationAnalyzer(dataFetchService, appProperties);
    }

    private static KlineData k(double open, double close, double high, double low) {
        KlineData kd = new KlineData();
        kd.setOpen(String.valueOf(open));
        kd.setClose(String.valueOf(close));
        kd.setHigh(String.valueOf(high));
        kd.setLow(String.valueOf(low));
        kd.setQuoteAssetVolume("1000");
        return kd;
    }

    /** 40天历史价格 20→11 下跌, 之后 5 天在 10 附近横盘; pinDay 为长下影插针。 */
    private static List<KlineData> series(KlineData pinDay) {
        List<KlineData> list = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double p = 20 - i * 9.0 / 39;
            list.add(k(p, p, p + 0.1, p - 0.1));
        }
        list.add(k(10.0, 10.05, 10.2, 9.9));
        list.add(k(10.05, 10.0, 10.2, 9.9));
        list.add(pinDay);
        list.add(k(10.0, 10.05, 10.2, 9.9));
        list.add(k(10.05, 10.1, 10.2, 9.95));
        return list;
    }

    private AnalysisReport run(List<KlineData> klines) {
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt())).thenReturn(Map.of("XUSDT", klines));
        return analyzer.analyze(List.of("XUSDT"));
    }

    @Test
    void matchesLowConsolidationWithLongLowerWick() {
        // 实体 0.05, 下影 1.0 (占振幅 ~87%) = 有效插针
        AnalysisReport r = run(series(k(10.0, 10.05, 10.1, 9.0)));

        assertEquals(1, r.getMatchedCount());
        assertTrue(r.getCoins().get(0).getDetail().contains("插针1次"), r.getCoins().get(0).getDetail());
    }

    @Test
    void ordinaryShadowsAreNotPins() {
        // 原实现"影线长于实体"即算插针 → 普通小影线也会命中; 新规则要求下影占振幅过半
        AnalysisReport r = run(series(k(10.0, 10.05, 10.4, 9.9)));
        assertEquals(0, r.getMatchedCount());
    }

    @Test
    void skipsWhenNotLowPrice() {
        List<KlineData> klines = series(k(10.0, 10.05, 10.1, 9.0));
        // 历史价格整体低于横盘价 → 横盘处于高位
        for (int i = 0; i < 40; i++) klines.set(i, k(5, 5, 5.1, 4.9));
        assertEquals(0, run(klines).getMatchedCount());
    }

    @Test
    void skipsWhenHistoryTooShort() {
        List<KlineData> klines = series(k(10.0, 10.05, 10.1, 9.0)).subList(30, 45);
        assertEquals(0, run(klines).getMatchedCount());
    }
}
