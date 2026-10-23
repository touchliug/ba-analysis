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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OiDivergenceAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;

    @Mock
    private AppProperties appProperties;

    private OiDivergenceAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        AppProperties.AnalysisConfig ac = new AppProperties.AnalysisConfig();
        AppProperties.OiDivergenceConfig cfg = new AppProperties.OiDivergenceConfig();
        cfg.setDays(3);
        cfg.setMinOiRisePercent(15);
        cfg.setMinOiUpDays(3);
        ac.setOiDivergence(cfg);
        lenient().when(appProperties.getAnalysis()).thenReturn(ac);
        analyzer = new OiDivergenceAnalyzer(dataFetchService, appProperties);
    }

    private static List<KlineData> closes(double... closes) {
        List<KlineData> list = new ArrayList<>();
        for (double c : closes) {
            KlineData k = new KlineData();
            k.setOpen(String.valueOf(c));
            k.setHigh(String.valueOf(c + 1));
            k.setLow(String.valueOf(c - 1));
            k.setClose(String.valueOf(c));
            k.setQuoteAssetVolume("1000");
            list.add(k);
        }
        return list;
    }

    private static List<OpenInterestData> oi(double... values) {
        List<OpenInterestData> list = new ArrayList<>();
        for (double v : values) {
            OpenInterestData o = new OpenInterestData();
            o.setSumOpenInterest(String.valueOf(v));
            list.add(o);
        }
        return list;
    }

    private AnalysisReport run(List<KlineData> klines, List<OpenInterestData> oiSeries, Double funding) {
        when(dataFetchService.fetchDailyKlines(anyList(), anyInt())).thenReturn(Map.of("XUSDT", klines));
        when(dataFetchService.fetchOpenInterestHistoryBatch(anyList(), anyInt())).thenReturn(Map.of("XUSDT", oiSeries));
        when(dataFetchService.getLastFundingRates()).thenReturn(funding == null ? Map.of() : Map.of("XUSDT", funding));
        return analyzer.analyze(List.of("XUSDT"));
    }

    @Test
    void bullishWhenOiUpAndPriceFlat() {
        AnalysisReport r = run(closes(100, 101, 102, 103), oi(1000, 1080, 1150, 1250), 0.0001);

        assertEquals(1, r.getMatchedCount());
        AnalysisReport.CoinAnalysis c = r.getCoins().get(0);
        assertEquals("long", c.getSide());
        assertEquals(25.0, c.getChangePercent(), 1e-9);
        assertTrue(c.getDetail().startsWith("[偏多] 3天持仓+25.0%(3/3天增) 价格+3.00%"), c.getDetail());
    }

    @Test
    void bearishWhenOiUpAndPriceDown() {
        AnalysisReport r = run(closes(100, 98, 96, 95), oi(1000, 1080, 1150, 1250), -0.001);

        assertEquals(1, r.getMatchedCount());
        assertEquals("short", r.getCoins().get(0).getSide());
        assertTrue(r.getCoins().get(0).getDetail().startsWith("[偏空][空头拥挤]"));
    }

    @Test
    void marksCrowdedLongs() {
        AnalysisReport r = run(closes(100, 101, 102, 103), oi(1000, 1080, 1150, 1250), 0.001);
        assertTrue(r.getCoins().get(0).getDetail().startsWith("[偏多][多头拥挤]"));
    }

    @Test
    void rejectsSmallOiRise() {
        assertEquals(0, run(closes(100, 101, 102, 103), oi(1000, 1030, 1060, 1100), null).getMatchedCount());
    }

    @Test
    void rejectsWhenOiNotRisingEnoughDays() {
        assertEquals(0, run(closes(100, 101, 102, 103), oi(1000, 1300, 1200, 1250), null).getMatchedCount());
    }

    @Test
    void rejectsPriceOutsideRange() {
        // 价格暴跌 -30%: 超出默认 -10% 下限 (原"价涨仓增"无下限会误收)
        assertEquals(0, run(closes(100, 90, 80, 70), oi(1000, 1080, 1150, 1250), null).getMatchedCount());
    }
}
