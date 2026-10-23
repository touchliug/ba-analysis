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

@ExtendWith(MockitoExtension.class)
class BullishAccumulationAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;

    @Mock
    private AppProperties appProperties;

    private BullishAccumulationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new BullishAccumulationAnalyzer(dataFetchService, appProperties);
    }

    private static KlineData k(double high, double low, double close, double vol) {
        KlineData kd = new KlineData();
        kd.setOpen(String.valueOf(close));
        kd.setHigh(String.valueOf(high));
        kd.setLow(String.valueOf(low));
        kd.setClose(String.valueOf(close));
        kd.setQuoteAssetVolume(String.valueOf(vol));
        return kd;
    }

    /** 回溯期从 200 跌到 110; 窗口 10 天在底部, 后半段放量、振幅收敛。 */
    private static List<KlineData> series(boolean higherLow) {
        List<KlineData> list = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double p = 200 - i * 4.5;
            list.add(k(p + 2, p - 2, p, 1000));
        }
        for (int i = 0; i < 5; i++) list.add(k(104, 96, 100, 1000));
        double secondLow = higherLow ? 98 : 94;
        for (int i = 0; i < 5; i++) list.add(k(102, secondLow, 100, 2500));
        return list;
    }

    private Map<String, Object> params(boolean requireHigherLow) {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("days", 10);
        p.put("minScore", 0);
        p.put("priceLookbackDays", 20);
        p.put("drawdownMin", 10.0);
        p.put("requireHigherLow", requireHigherLow);
        p.put("volumeIncreaseMin", 1.2);
        p.put("rangeContractionMax", 0.8);
        return p;
    }

    @Test
    void requireHigherLowIsAHardFilter() {
        // 原实现仅给 H=0 分, 其余维度高分照样输出
        List<AnalysisReport.CoinAnalysis> r = analyzer.doAnalyze(Map.of("XUSDT", series(false)), params(true));
        assertTrue(r.isEmpty());
    }

    @Test
    void lowerLowAllowedWhenNotRequired() {
        assertEquals(1, analyzer.doAnalyze(Map.of("XUSDT", series(false)), params(false)).size());
    }

    @Test
    void matchesWhenLowsRise() {
        List<AnalysisReport.CoinAnalysis> r = analyzer.doAnalyze(Map.of("XUSDT", series(true)), params(true));
        assertEquals(1, r.size());
        assertTrue(r.get(0).getScore() >= 60, "底部+低点抬高+放量+收敛应高分, 实际=" + r.get(0).getScore());
    }
}
