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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class AltcoinPumpAlertAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;

    @Mock
    private AppProperties appProperties;

    private AltcoinPumpAlertAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new AltcoinPumpAlertAnalyzer(dataFetchService, appProperties);
    }

    private static KlineData k(double open, double high, double low, double close, double vol) {
        KlineData kd = new KlineData();
        kd.setOpen(String.valueOf(open));
        kd.setHigh(String.valueOf(high));
        kd.setLow(String.valueOf(low));
        kd.setClose(String.valueOf(close));
        kd.setQuoteAssetVolume(String.valueOf(vol));
        return kd;
    }

    private static Map<String, Object> params() {
        Map<String, Object> p = new HashMap<>();
        p.put("days", 7);
        p.put("minScore", 0);
        p.put("maxPriceDeclinePercent", 15.0);
        p.put("oiConsecutiveDays", 3);
        p.put("volumeExpansionRatio", 1.5);
        p.put("lowPricePercentile", 35.0);
        p.put("wickBodyRatio", 1.5);
        p.put("silentBuyerRatio", 1.3);
        p.put("maxDrawdownPercent", 25.0);
        return p;
    }

    /**
     * 当日盘中插针到 2.00 后收在 1.40: 旧实现用「含当日的窗口最高价」算回撤,
     * 得到 (2.00-1.40)/2.00 = 30% > 25%, 把自己淘汰。回撤应只看前期高点。
     */
    @Test
    void intradaySpikeOnSignalDayIsNotCountedAsDrawdown() {
        List<KlineData> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) list.add(k(1.00, 1.02, 0.98, 1.00, 1000));
        list.add(k(1.00, 2.00, 0.99, 1.40, 5000));

        List<AnalysisReport.CoinAnalysis> r = analyzer.doAnalyze(Map.of("XUSDT", list), params());

        assertEquals(1, r.size(), "当日插针不应被回撤门槛淘汰");
    }

    /** 前期高点 2.00、现价 1.45, 真实回撤 27.5%: 门槛必须继续拦下。 */
    @Test
    void drawdownFromEarlierHighIsStillFiltered() {
        List<KlineData> list = new ArrayList<>();
        list.add(k(1.40, 1.45, 1.38, 1.40, 1000));
        list.add(k(1.40, 2.00, 1.39, 1.42, 3000));
        for (int i = 0; i < 4; i++) list.add(k(1.42, 1.46, 1.40, 1.42, 1000));
        list.add(k(1.42, 1.48, 1.40, 1.45, 1000));

        List<AnalysisReport.CoinAnalysis> r = analyzer.doAnalyze(Map.of("XUSDT", list), params());

        assertEquals(0, r.size(), "从前期高点回落 27.5% 仍应被淘汰");
    }
}
