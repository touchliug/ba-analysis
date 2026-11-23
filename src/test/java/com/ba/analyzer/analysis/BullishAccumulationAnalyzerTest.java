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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

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
        lenient().when(dataFetchService.fetchOpenInterestHistoryBatch(anyList(), anyInt()))
                .thenReturn(Collections.emptyMap());
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

    private static OpenInterestData oi(double coins) {
        OpenInterestData d = new OpenInterestData();
        d.setSumOpenInterest(String.valueOf(coins));
        d.setSumOpenInterestValue(String.valueOf(coins * 10));
        return d;
    }

    private static Map<String, Object> defaultParams() {
        Map<String, Object> p = new HashMap<>();
        p.put("lookbackDays", 60);
        p.put("minScore", 65);
        p.put("minWaveRisePercent", 40.0);
        p.put("waveDrawdownMin", 25.0);
        p.put("waveDrawdownMax", 55.0);
        p.put("maxDryVolumeRatio", 0.15);
        p.put("lowPricePercentile", 45.0);
        p.put("minOiRisePercent", 15.0);
        p.put("maxDailyRisePercent", 8.0);
        p.put("minLiquidityUsdt", 2_000_000.0);
        return p;
    }

    /**
     * 模拟 EVAA 2026-11-13 形态:
     * 前60天内有5日主升(+150%, 峰值5日均额 2亿U), 12天前见顶 2.50,
     * 随后回调至 1.55(回撤38%), 近5日缩量至 600万U(峰值3%), 最后2日温和连阳(+1%, +0.7%)。
     */
    private static List<KlineData> secondWavePitSeries(double dayMinus3Close, double lastOpen, double lastClose) {
        List<KlineData> list = new ArrayList<>();
        // Day 0..42 (43 bars): 底部平稳 1.00, 日额 1000万
        for (int i = 0; i < 43; i++) {
            list.add(k(1.00, 1.03, 0.97, 1.00, 10_000_000));
        }
        // Day 43..47 (5 bars): 第一波主升 1.00 -> 2.50 (+150%), 日额 2亿U
        double[] waveCloses = {1.25, 1.55, 1.85, 2.20, 2.50};
        double prev = 1.00;
        for (double c : waveCloses) {
            list.add(k(prev, c * 1.02, prev * 0.98, c, 200_000_000));
            prev = c;
        }
        // Day 48..54 (7 bars): 从 2.50 回调到 1.56, 成交量逐步萎缩
        double[] pullback = {2.20, 2.00, 1.85, 1.72, 1.65, 1.60, 1.56};
        for (double c : pullback) {
            list.add(k(prev, prev * 1.01, c * 0.99, c, 25_000_000));
            prev = c;
        }
        // Day 55..59 (最后5根缩量坑, 日额 600万 = 峰值2亿的 3.0%):
        // Day 55 (e-4)
        list.add(k(1.56, 1.57, 1.53, 1.54, 6_000_000));
        // Day 56 (e-3): 用于控制3日涨跌幅基准
        list.add(k(1.54, dayMinus3Close * 1.01, dayMinus3Close * 0.99, dayMinus3Close, 6_000_000));
        // Day 57 (e-2)
        list.add(k(1.53, 1.55, 1.51, 1.52, 6_000_000));
        // Day 58 (e-1): 小阳线 1.52 -> 1.54 (+1.3%)
        list.add(k(1.52, 1.55, 1.51, 1.54, 6_000_000));
        // Day 59 (e): 当日 lastOpen -> lastClose
        list.add(k(lastOpen, Math.max(lastOpen, lastClose) * 1.01, Math.min(lastOpen, lastClose) * 0.99, lastClose, 6_000_000));
        return list;
    }

    @Test
    void catchesSecondWaveDryPullbackPit_likeEvaaOn1113() {
        List<KlineData> klines = secondWavePitSeries(1.53, 1.54, 1.55);
        List<AnalysisReport.CoinAnalysis> r = analyzer.doAnalyze(Map.of("EVAAUSDT", klines), defaultParams());

        assertEquals(1, r.size(), "EVAA 11-13 二波缩量坑应进池");
        AnalysisReport.CoinAnalysis hit = r.get(0);
        // 基础30 + 缩量3%(<=5% -> 30) + 前波150%(>=100% -> 20) + 连阳(15) = 95
        assertEquals(95, hit.getScore());
        assertTrue(hit.getDetail().contains("[二波缩量坑]"), hit.getDetail());
        assertTrue(hit.getDetail().contains("盯盘点火"), hit.getDetail());
    }

    @Test
    void rejectsCoinStillFallingHardIn3Days() {
        // e-3 收盘 1.80 -> e 收盘 1.55, 3日跌幅 -13.9% < -6%, 仍在急跌接飞刀阶段
        List<KlineData> klines = secondWavePitSeries(1.80, 1.54, 1.55);
        List<AnalysisReport.CoinAnalysis> r = analyzer.doAnalyze(Map.of("EVAAUSDT", klines), defaultParams());

        assertTrue(r.isEmpty(), "3日跌幅超过6%的急跌阶段不应进池");
    }

    @Test
    void rejectsCoinAlreadyBrokenOutToday() {
        // 当日从 1.54 涨到 1.75 (+13.6% >= 8.0%), 已启动不再属于潜伏池
        List<KlineData> klines = secondWavePitSeries(1.62, 1.54, 1.75);
        List<AnalysisReport.CoinAnalysis> r = analyzer.doAnalyze(Map.of("EVAAUSDT", klines), defaultParams());

        assertTrue(r.isEmpty(), "当日涨幅>=8%已启动的币不应留在潜伏观察池");
    }

    @Test
    void rejectsWhenSwingHighIsTooRecent() {
        List<KlineData> list = new ArrayList<>();
        for (int i = 0; i < 53; i++) list.add(k(1.00, 1.02, 0.98, 1.00, 10_000_000));
        // 高点 2.50 发生在 e-2 (仅2天前 < 5天)
        list.add(k(1.00, 1.40, 0.99, 1.35, 200_000_000));
        list.add(k(1.35, 1.80, 1.30, 1.75, 200_000_000));
        list.add(k(1.75, 2.20, 1.70, 2.15, 200_000_000));
        list.add(k(2.15, 2.40, 2.10, 2.35, 200_000_000));
        list.add(k(2.35, 2.50, 1.50, 1.55, 6_000_000)); // e-2 见顶 2.50
        list.add(k(1.55, 1.57, 1.52, 1.56, 6_000_000));
        list.add(k(1.56, 1.58, 1.54, 1.57, 6_000_000));

        List<AnalysisReport.CoinAnalysis> r = analyzer.doAnalyze(Map.of("NEWTOPUSDT", list), defaultParams());
        assertTrue(r.isEmpty(), "见顶不足5天的币不应算二波缩量坑");
    }

    @Test
    void catchesFirstWaveOiAccumulationInLowRange() {
        List<KlineData> list = new ArrayList<>();
        // 前40天在 1.50, 后19天在 1.03 窄幅横盘, 最后一日探底回升收阳 0.99 -> 1.01 (处于60日25%分位以下)
        for (int i = 0; i < 40; i++) list.add(k(1.50, 1.53, 1.47, 1.50, 8_000_000));
        for (int i = 0; i < 19; i++) list.add(k(1.03, 1.05, 1.01, 1.03, 5_000_000));
        // 最后一日温和收阳 0.99 -> 1.01 (+2.0%)
        list.add(k(0.99, 1.02, 0.98, 1.01, 5_000_000));

        // 6个日线币本位OI点: 100 -> 105 -> 112 -> 110 -> 120 -> 130 (5日净增+30%, 4天递增)
        List<OpenInterestData> ois = List.of(oi(100), oi(105), oi(112), oi(110), oi(120), oi(130));
        when(dataFetchService.fetchOpenInterestHistoryBatch(anyList(), anyInt()))
                .thenReturn(Map.of("PROMUSDT", ois));

        List<AnalysisReport.CoinAnalysis> r = analyzer.doAnalyze(Map.of("PROMUSDT", list), defaultParams());
        assertEquals(1, r.size(), "低位横盘+5日OI增30%应进首波潜伏池");
        // 基础30 + OI>=25%(35) + <=25%分位(10) + 当日收阳(10) = 85
        assertEquals(85, r.get(0).getScore());
        assertTrue(r.get(0).getDetail().contains("[首波OI潜伏]"), r.get(0).getDetail());
    }

    @Test
    void catchesFirstWaveFootprintWhenOiMissing() {
        List<KlineData> list = new ArrayList<>();
        for (int i = 0; i < 40; i++) list.add(k(1.50, 1.53, 1.47, 1.50, 8_000_000));
        for (int i = 0; i < 13; i++) list.add(k(1.03, 1.05, 1.01, 1.03, 6_000_000));
        // e-6: 放巨量试盘针(成交额 3000万 >= 20日均量3x, 振幅 (1.14-0.99)/0.99=15.1% >= 8%, 收盘涨 +3%)
        list.add(k(1.00, 1.14, 0.99, 1.03, 30_000_000));
        for (int i = 0; i < 3; i++) list.add(k(1.03, 1.05, 1.01, 1.03, 5_000_000));
        // 最近3日极度缩量(日额 200万 <= 20日均量的 0.45x), 且最后一日在低位收阳 0.99 -> 1.01
        list.add(k(1.02, 1.03, 1.01, 1.02, 2_000_000));
        list.add(k(1.02, 1.03, 1.01, 1.02, 2_000_000));
        list.add(k(0.99, 1.02, 0.98, 1.01, 2_000_000));

        List<AnalysisReport.CoinAnalysis> r = analyzer.doAnalyze(Map.of("BMTUSDT", list), defaultParams());
        assertEquals(1, r.size(), "无OI时低位巨量试盘针+近3日缩量回踩应进池");
        // 基础30 + 试盘缩量<=0.45x(25) + <=25%分位(10) + 当日收阳(10) = 75
        assertEquals(75, r.get(0).getScore());
        assertTrue(r.get(0).getDetail().contains("[首波试盘潜伏]"), r.get(0).getDetail());
    }
}
