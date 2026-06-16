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
class ReversalLongAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;
    @Mock
    private AppProperties appProperties;

    private ReversalLongAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ReversalLongAnalyzer(dataFetchService, appProperties);
    }

    private Map<String, Object> params() {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("declineMinDays", 4);
        p.put("declineMinPct", 5.0);
        p.put("declineMaxPct", 40.0);
        p.put("reversalMinPct", 1.0);
        p.put("reversalMaxPct", 20.0);
        p.put("takeProfitPct", 6.0);
        p.put("stopLossPct", 4.0);
        p.put("volumeConfirmRatio", 1.0);
        p.put("qualityMinScore", 0);
        return p;
    }

    /** open,high,low,close,quoteVol → KlineData */
    private KlineData k(double o, double h, double l, double c, double qv) {
        KlineData kd = new KlineData();
        kd.setOpen(String.valueOf(o));
        kd.setHigh(String.valueOf(h));
        kd.setLow(String.valueOf(l));
        kd.setClose(String.valueOf(c));
        kd.setVolume(String.valueOf(qv));
        kd.setQuoteAssetVolume(String.valueOf(qv));
        kd.setOpenTime(System.currentTimeMillis());
        return kd;
    }

    /**
     * 构造连跌后反转的序列。
     * @param highQuality true=高质量(连跌5天、跌幅~6%、反转~1.2%、短下影、收盘下半区);
     *                    false=低质量(连跌5天、跌幅大、反转~3.5%、长下影、收盘上半区)
     */
    private List<KlineData> buildSeries(boolean highQuality) {
        List<KlineData> list = new ArrayList<>();
        // ref day (decline 前一天)
        list.add(k(100, 101, 99, 100, 1000));
        if (highQuality) {
            // 连跌5天累跌~6.5%: 100→98.5→97.2→95.8→94.5→93.5
            double[] closes = {98.5, 97.2, 95.8, 94.5, 93.5};
            double prev = 100;
            for (double c : closes) { list.add(k(prev, prev + 0.3, c - 0.3, c, 1000)); prev = c; }
            // 反转日: 末日收93.5, 温和小阳+1.2%→94.6。
            // 短下影(low 94.3≈body底94.5) + 收盘落在下半区:
            // open 94.5, close 94.6, high 96.0, low 94.3 → range1.7, 下影0.12, 收盘位置0.18
            list.add(k(94.5, 96.0, 94.3, 94.6, 1000));
            list.add(k(94.6, 95.6, 93.6, 94.6, 1000)); // entry day
        } else {
            // 连跌5天累跌~22%: 100→94→88→83→79→78
            double[] closes = {94, 88, 83, 79, 78};
            double prev = 100;
            for (double c : closes) { list.add(k(prev, prev + 0.5, c - 0.5, c, 1000)); prev = c; }
            // 反转日: 末日78, 大涨+3.5%→80.7, 长下影(low 72), 收盘冲到上半区高位
            list.add(k(79, 81, 72, 80.7, 4000));
            list.add(k(80.7, 81.7, 79.7, 80.7, 1000)); // entry day
        }
        return list;
    }

    @Test
    void highQuality_highScore() {
        Map<String, List<KlineData>> klineMap = Map.of("HIGHUSDT", buildSeries(true));
        List<AnalysisReport.CoinAnalysis> r = analyzer.doAnalyze(klineMap, params());
        assertEquals(1, r.size(), "应匹配到反转信号");
        Integer score = r.get(0).getScore();
        assertNotNull(score);
        assertTrue(score >= 70, "连跌5天+小跌幅+温和反转+短下影+低收盘应得高质量分, 实际=" + score);
    }

    @Test
    void lowQuality_lowScore() {
        Map<String, List<KlineData>> klineMap = Map.of("LOWUSDT", buildSeries(false));
        List<AnalysisReport.CoinAnalysis> r = analyzer.doAnalyze(klineMap, params());
        assertEquals(1, r.size());
        Integer score = r.get(0).getScore();
        assertNotNull(score);
        assertTrue(score < 50, "大跌幅+大反转+长下影+高收盘应得低质量分, 实际=" + score);
    }

    @Test
    void minScoreFilter_dropsLowQuality() {
        Map<String, Object> p = params();
        p.put("qualityMinScore", 60);
        Map<String, List<KlineData>> klineMap = Map.of("LOWUSDT", buildSeries(false));
        List<AnalysisReport.CoinAnalysis> r = analyzer.doAnalyze(klineMap, p);
        assertTrue(r.isEmpty(), "低质量信号应被qualityMinScore=60过滤");
    }

    @Test
    void staleData_isFiltered() {
        // 最新K线收盘时间是2天前 → 数据滞后, 应被新鲜度校验过滤
        List<KlineData> series = buildSeries(true);
        long twoDaysAgo = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000;
        series.get(series.size() - 1).setCloseTime(twoDaysAgo);
        List<AnalysisReport.CoinAnalysis> r =
                analyzer.doAnalyze(Map.of("STALEUSDT", series), params());
        assertTrue(r.isEmpty(), "滞后超过1天的数据应被跳过");
    }

    @Test
    void freshData_passes() {
        // 最新K线为当日未收盘 (closeTime在未来) → 正常通过
        List<KlineData> series = buildSeries(true);
        long futureClose = System.currentTimeMillis() + 60_000;
        series.get(series.size() - 1).setCloseTime(futureClose);
        List<AnalysisReport.CoinAnalysis> r =
                analyzer.doAnalyze(Map.of("FRESHUSDT", series), params());
        assertEquals(1, r.size(), "当日未收盘数据应正常匹配");
    }

    @Test
    void sortByScoreDescending() {
        List<AnalysisReport.CoinAnalysis> coins = new ArrayList<>();
        coins.addAll(analyzer.doAnalyze(Map.of("LOWUSDT", buildSeries(false)), params()));
        coins.addAll(analyzer.doAnalyze(Map.of("HIGHUSDT", buildSeries(true)), params()));
        analyzer.sortResults(coins);
        assertEquals(2, coins.size());
        assertTrue(coins.get(0).getScore() >= coins.get(1).getScore(), "应按质量分降序");
        assertEquals("HIGHUSDT", coins.get(0).getSymbol());
    }
}
