package com.ba.analyzer.service;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.model.OpenInterestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动扫描四个捕捉器。
 *
 * <p>重点锁住：只用已收盘 K 线、流动性门槛、形态边界（旗形振幅 / 回踩深度 / 不破启动前收盘）、
 * 以及「一次加载服务四个 type」的缓存语义。
 */
class SetupScanServiceTest {

    private static final long H = 3_600_000L;
    private static final long D = 86_400_000L;

    private DataFetchService dataFetch;
    private SymbolService symbols;
    private AppProperties props;
    private SetupScanService service;
    private long now;

    @BeforeEach
    void setUp() {
        dataFetch = mock(DataFetchService.class);
        symbols = mock(SymbolService.class);
        props = new AppProperties();
        service = new SetupScanService(dataFetch, symbols, props, 5_000L, System::currentTimeMillis);
        when(symbols.getSymbolNames()).thenReturn(List.of("AAAUSDT"));
        when(dataFetch.getLastFundingRates()).thenReturn(Map.of("AAAUSDT", 0.0002));
        now = (System.currentTimeMillis() / H) * H;   // 当前整点，最后一根已收盘 = now - H
    }

    // ======================== 构造工具 ========================

    private static KlineData bar(long openTime, double open, double high, double low,
                                 double close, double quoteVol) {
        KlineData k = new KlineData();
        k.setOpenTime(openTime);
        k.setCloseTime(openTime + H - 1);
        k.setOpen(String.valueOf(open));
        k.setHigh(String.valueOf(high));
        k.setLow(String.valueOf(low));
        k.setClose(String.valueOf(close));
        k.setQuoteAssetVolume(String.valueOf(quoteVol));
        return k;
    }

    /** n 根平价 1h 线，最后一根 openTime = lastOpen。 */
    private static List<KlineData> flatBars(long lastOpen, int n, double price, double vol) {
        List<KlineData> list = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            list.add(bar(lastOpen - i * H, price, price, price, price, vol));
        }
        return list;
    }

    private static KlineData daily(long openTime, double high, double low, double close) {
        KlineData k = new KlineData();
        k.setOpenTime(openTime);
        k.setCloseTime(openTime + D - 1);
        k.setOpen(String.valueOf(close));
        k.setHigh(String.valueOf(high));
        k.setLow(String.valueOf(low));
        k.setClose(String.valueOf(close));
        k.setQuoteAssetVolume("1000000");
        return k;
    }

    private List<KlineData> flatDaily(int n, double high, double low, double close) {
        List<KlineData> list = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            list.add(daily(now - 2 * D - i * D, high, low, close));
        }
        return list;
    }

    private static OpenInterestData oi(long ts, double coin) {
        OpenInterestData o = new OpenInterestData();
        o.setTimestamp(ts);
        o.setSumOpenInterest(String.valueOf(coin));
        o.setSumOpenInterestValue(String.valueOf(coin * 2));
        return o;
    }

    private static LongShortRatioData lsr(String type, long ts, double ratio) {
        LongShortRatioData r = new LongShortRatioData();
        r.setSymbol("AAAUSDT");
        r.setRatioType(type);
        r.setPeriod("1h");
        r.setTimestamp(ts);
        r.setLongShortRatio(String.valueOf(ratio));
        return r;
    }

    private void stub(List<KlineData> h1, List<KlineData> d1) {
        when(dataFetch.fetchKlines(anyList(), eq("1h"), anyInt())).thenReturn(Map.of("AAAUSDT", h1));
        when(dataFetch.fetchKlines(anyList(), eq("1d"), anyInt())).thenReturn(Map.of("AAAUSDT", d1));
    }

    // ======================== 1. 放量突破 ========================

    @Test
    void volumeSpikeMatchesIgnitionBar() {
        List<KlineData> h1 = flatBars(now - 2 * H, 24, 1.0, 1_000_000);
        h1.add(bar(now - H, 1.0, 2.0, 0.9, 1.9, 50_000_000));
        stub(h1, flatDaily(20, 1.5, 1.0, 1.2));

        SetupScanService.ScanResult r = service.scanAll().get(SetupScanService.TYPE_VOLUME_SPIKE);

        assertThat(r.matchedCount()).isEqualTo(1);
        SetupScanService.SetupMatch m = r.matches().get(0);
        assertThat(m.symbol()).isEqualTo("AAAUSDT");
        assertThat(m.changePercent()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.volumeRatio()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(m.breakoutPrice()).isCloseTo(1.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.riskReward()).isGreaterThan(0);
    }

    @Test
    void volumeSpikeRejectsLowVolume() {
        List<KlineData> h1 = flatBars(now - 2 * H, 24, 1.0, 1_000_000);
        h1.add(bar(now - H, 1.0, 2.0, 0.9, 1.9, 5_000_000));   // 只有 5 倍
        stub(h1, flatDaily(20, 1.5, 1.0, 1.2));

        assertThat(service.scanAll().get(SetupScanService.TYPE_VOLUME_SPIKE).matchedCount()).isZero();
    }

    @Test
    void volumeSpikeRejectsWithoutBreakout() {
        List<KlineData> h1 = flatBars(now - 2 * H, 24, 1.0, 1_000_000);
        h1.add(bar(now - H, 1.0, 1.45, 0.9, 1.4, 50_000_000));   // 收 1.4 < 20 日高 1.5
        stub(h1, flatDaily(20, 1.5, 1.0, 1.2));

        assertThat(service.scanAll().get(SetupScanService.TYPE_VOLUME_SPIKE).matchedCount()).isZero();
    }

    // ======================== 2. 高位旗形 ========================

    @Test
    void flagBreakoutMatches() {
        List<KlineData> h1 = new ArrayList<>();
        for (int i = 19; i >= 11; i--) h1.add(bar(now - H - i * H, 3.0, 3.0, 3.0, 3.0, 1_000_000));
        for (int i = 10; i >= 1; i--) h1.add(bar(now - H - i * H, 3.05, 3.2, 3.0, 3.05, 1_000_000));
        h1.add(bar(now - H, 3.1, 3.35, 3.05, 3.3, 2_000_000));

        List<KlineData> d1 = flatDaily(32, 2.1, 1.9, 2.0);
        d1.set(1, daily(now - 2 * D - 30 * D, 0.6, 0.4, 0.5));   // 30 日前的基准收盘
        stub(h1, d1);

        SetupScanService.ScanResult r = service.scanAll().get(SetupScanService.TYPE_FLAG_BREAKOUT);

        assertThat(r.matchedCount()).isEqualTo(1);
        SetupScanService.SetupMatch m = r.matches().get(0);
        assertThat(m.breakoutPrice()).isCloseTo(3.2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.stopPrice()).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.priorGainPercent()).isGreaterThan(1.0);
        assertThat(m.riskReward()).isGreaterThan(1.0);
    }

    @Test
    void flagBreakoutRejectsWideRange() {
        List<KlineData> h1 = new ArrayList<>();
        for (int i = 19; i >= 11; i--) h1.add(bar(now - H - i * H, 3.0, 3.0, 3.0, 3.0, 1_000_000));
        // 旗形振幅 20%（3.0 ~ 3.6），超过 8% 上限
        for (int i = 10; i >= 1; i--) h1.add(bar(now - H - i * H, 3.05, 3.6, 3.0, 3.05, 1_000_000));
        h1.add(bar(now - H, 3.1, 3.7, 3.05, 3.65, 2_000_000));

        List<KlineData> d1 = flatDaily(32, 2.1, 1.9, 2.0);
        d1.set(1, daily(now - 2 * D - 30 * D, 0.6, 0.4, 0.5));
        stub(h1, d1);

        assertThat(service.scanAll().get(SetupScanService.TYPE_FLAG_BREAKOUT).matchedCount()).isZero();
    }

    // ======================== 3. 突破回踩 ========================

    /** 44 根横盘 → 脉冲 +90% / 40 倍量 → 回踩 63.8% 缩量 → 放量收阳确认。 */
    private List<KlineData> pullbackShape() {
        long spikeT = now - 6 * H;
        List<KlineData> h1 = flatBars(now - 7 * H, 44, 1.0, 1_000_000);
        h1.add(bar(spikeT, 1.0, 2.0, 0.95, 1.9, 40_000_000));
        h1.add(bar(spikeT + H, 1.9, 1.95, 1.7, 1.75, 20_000_000));
        h1.add(bar(spikeT + 2 * H, 1.75, 1.8, 1.5, 1.55, 10_000_000));
        h1.add(bar(spikeT + 3 * H, 1.55, 1.6, 1.4, 1.45, 8_000_000));
        h1.add(bar(spikeT + 4 * H, 1.45, 1.5, 1.35, 1.4, 7_000_000));
        h1.add(bar(now - H, 1.4, 1.55, 1.33, 1.52, 12_000_000));   // 确认根
        return h1;
    }

    @Test
    void pullbackConfirmMatchesAfterDeepRetrace() {
        stub(pullbackShape(), flatDaily(20, 2.0, 1.0, 1.2));

        SetupScanService.ScanResult r = service.scanAll().get(SetupScanService.TYPE_PULLBACK_CONFIRM);

        assertThat(r.matchedCount()).isEqualTo(1);
        SetupScanService.SetupMatch m = r.matches().get(0);
        assertThat(m.stopPrice()).isCloseTo(1.33, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.targetPrice()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.rangePercent()).isBetween(0.382, 0.786);
        assertThat(m.riskReward()).isGreaterThan(2.0);
    }

    @Test
    void pullbackRejectsWhenItBreaksPreSpikeClose() {
        List<KlineData> h1 = pullbackShape();
        int i = h1.size() - 2;                                     // 回踩深处插一根跌破启动前收盘
        h1.set(i, bar(h1.get(i).getOpenTime(), 1.55, 1.6, 0.8, 0.9, 7_000_000));
        stub(h1, flatDaily(20, 2.0, 1.0, 1.2));

        assertThat(service.scanAll().get(SetupScanService.TYPE_PULLBACK_CONFIRM).matchedCount()).isZero();
    }

    @Test
    void pullbackRejectsWhenRetraceTooShallow() {
        long spikeT = now - 6 * H;
        List<KlineData> h1 = flatBars(now - 7 * H, 44, 1.0, 1_000_000);
        h1.add(bar(spikeT, 1.0, 2.0, 0.95, 1.9, 40_000_000));
        for (int k = 1; k <= 4; k++) h1.add(bar(spikeT + k * H, 1.9, 1.9, 1.85, 1.88, 5_000_000));
        h1.add(bar(now - H, 1.88, 1.95, 1.85, 1.93, 6_000_000));   // 只回踩 5%
        stub(h1, flatDaily(20, 2.0, 1.0, 1.2));

        assertThat(service.scanAll().get(SetupScanService.TYPE_PULLBACK_CONFIRM).matchedCount()).isZero();
    }

    // ======================== 4. 散户空头拥挤 ========================

    private void stubRetail(List<LongShortRatioData> retail, List<LongShortRatioData> top,
                            List<OpenInterestData> oiList) {
        stub(flatBars(now - H, 25, 2.0, 1_000_000), flatDaily(20, 2.2, 1.8, 2.0));
        when(dataFetch.getLongShortRatioBatch(anyList(), eq("GLOBAL_ACCOUNT"), anyString(), anyInt()))
                .thenReturn(Map.of("AAAUSDT", retail));
        when(dataFetch.getLongShortRatioBatch(anyList(), eq("TOP_POSITION"), anyString(), anyInt()))
                .thenReturn(Map.of("AAAUSDT", top));
        when(dataFetch.fetchOpenInterestHistoryBatch(anyList(), eq("1h"), anyInt()))
                .thenReturn(Map.of("AAAUSDT", oiList));
    }

    private List<LongShortRatioData> topRising() {
        List<LongShortRatioData> top = new ArrayList<>();
        for (int i = 9; i >= 0; i--) {
            top.add(lsr("TOP_POSITION", now - H - i * H, i > 5 ? 1.40 : 1.50));
        }
        return top;
    }

    private List<OpenInterestData> flatOi() {
        List<OpenInterestData> oiList = new ArrayList<>();
        for (int i = 29; i >= 0; i--) oiList.add(oi(now - H - i * H, 1_000));
        return oiList;
    }

    @Test
    void retailShortMatchesCrowdedShortsWithRisingTopPosition() {
        List<LongShortRatioData> retail = List.of(
                lsr("GLOBAL_ACCOUNT", now - 4 * H, 0.50),
                lsr("GLOBAL_ACCOUNT", now - 3 * H, 0.47),
                lsr("GLOBAL_ACCOUNT", now - 2 * H, 0.45),
                lsr("GLOBAL_ACCOUNT", now - H, 0.44));
        stubRetail(retail, topRising(), flatOi());

        SetupScanService.ScanResult r = service.scanAll().get(SetupScanService.TYPE_RETAIL_SHORT);

        assertThat(r.matchedCount()).isEqualTo(1);
        SetupScanService.SetupMatch m = r.matches().get(0);
        assertThat(m.retailRatio()).isCloseTo(0.44, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.topPositionRatio()).isCloseTo(1.50, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.fundingRate()).isCloseTo(0.0002, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void retailShortRejectsWhenRetailNotCrowded() {
        List<LongShortRatioData> retail = List.of(lsr("GLOBAL_ACCOUNT", now - H, 0.90));
        stubRetail(retail, topRising(), flatOi());

        assertThat(service.scanAll().get(SetupScanService.TYPE_RETAIL_SHORT).matchedCount()).isZero();
    }

    @Test
    void retailShortRejectsWhenTopPositionFalling() {
        List<LongShortRatioData> retail = List.of(lsr("GLOBAL_ACCOUNT", now - H, 0.40));
        List<LongShortRatioData> top = new ArrayList<>();
        for (int i = 9; i >= 0; i--) {
            top.add(lsr("TOP_POSITION", now - H - i * H, i > 5 ? 1.60 : 1.35));
        }
        stubRetail(retail, top, flatOi());

        assertThat(service.scanAll().get(SetupScanService.TYPE_RETAIL_SHORT).matchedCount()).isZero();
    }

    // ======================== 公共门槛 ========================

    @Test
    void skipsIlliquidSymbols() {
        List<KlineData> h1 = flatBars(now - 2 * H, 24, 1.0, 10_000);       // 24h 只有 24 万
        h1.add(bar(now - H, 1.0, 2.0, 0.9, 1.9, 500_000));
        stub(h1, flatDaily(20, 1.5, 1.0, 1.2));

        Map<String, SetupScanService.ScanResult> r = service.scanAll();
        for (SetupScanService.ScanResult s : r.values()) {
            assertThat(s.matchedCount()).isZero();
        }
    }

    @Test
    void skipsStaleSymbols() {
        List<KlineData> h1 = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            h1.add(bar(now - 6 * H - i * H, 1.0, 1.0, 1.0, 1.0, 5_000_000));   // 六小时前就断了
        }
        stub(h1, flatDaily(20, 1.5, 1.0, 1.2));

        assertThat(service.scanAll().get(SetupScanService.TYPE_VOLUME_SPIKE).matchedCount()).isZero();
    }

    @Test
    void ignoresUnclosedBar() {
        List<KlineData> h1 = flatBars(now - 2 * H, 24, 1.0, 1_000_000);
        KlineData open = bar(now, 1.0, 2.0, 0.9, 1.9, 50_000_000);
        open.setCloseTime(now + H - 1);                                   // 未收盘
        h1.add(open);
        stub(h1, flatDaily(20, 1.5, 1.0, 1.2));

        assertThat(service.scanAll().get(SetupScanService.TYPE_VOLUME_SPIKE).matchedCount()).isZero();
    }

    @Test
    void scanAllReturnsAllFourSlots() {
        stub(flatBars(now - H, 25, 1.0, 5_000_000), flatDaily(20, 1.5, 1.0, 1.2));

        Map<String, SetupScanService.ScanResult> r = service.scanAll();

        assertThat(r).containsKeys(SetupScanService.TYPE_VOLUME_SPIKE,
                SetupScanService.TYPE_FLAG_BREAKOUT,
                SetupScanService.TYPE_PULLBACK_CONFIRM,
                SetupScanService.TYPE_RETAIL_SHORT);
        assertThat(r.get(SetupScanService.TYPE_VOLUME_SPIKE).totalSymbols()).isEqualTo(1);
    }

    // ======================== 缓存 / type ========================

    @Test
    void returnsCacheWhenNotRefreshing() {
        stub(flatBars(now - H, 25, 1.0, 5_000_000), flatDaily(20, 1.5, 1.0, 1.2));

        service.getLatest(SetupScanService.TYPE_VOLUME_SPIKE, false);
        service.getLatest(SetupScanService.TYPE_VOLUME_SPIKE, false);

        verify(dataFetch, times(1)).fetchKlines(anyList(), eq("1h"), anyInt());
    }

    @Test
    void refreshForcesRescan() {
        stub(flatBars(now - H, 25, 1.0, 5_000_000), flatDaily(20, 1.5, 1.0, 1.2));

        service.getLatest(SetupScanService.TYPE_VOLUME_SPIKE, true);
        service.getLatest(SetupScanService.TYPE_VOLUME_SPIKE, true);

        verify(dataFetch, times(2)).fetchKlines(anyList(), eq("1h"), anyInt());
    }

    @Test
    void allTypesShareOneLoad() {
        stub(flatBars(now - H, 25, 1.0, 5_000_000), flatDaily(20, 1.5, 1.0, 1.2));

        service.getLatest(SetupScanService.TYPE_VOLUME_SPIKE, true);
        service.getLatest(SetupScanService.TYPE_FLAG_BREAKOUT, false);
        service.getLatest(SetupScanService.TYPE_RETAIL_SHORT, false);

        verify(dataFetch, times(1)).fetchKlines(anyList(), eq("1h"), anyInt());
    }

    @Test
    void normalizesAndRejectsUnknownType() {
        assertThat(SetupScanService.normalizeType(null)).isEqualTo(SetupScanService.TYPE_VOLUME_SPIKE);
        assertThat(SetupScanService.normalizeType(" FLAG-BREAKOUT "))
                .isEqualTo(SetupScanService.TYPE_FLAG_BREAKOUT);
        assertThatThrownBy(() -> SetupScanService.normalizeType("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void springCanInstantiateTheService() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(DataFetchService.class, () -> mock(DataFetchService.class))
                .withBean(SymbolService.class, () -> mock(SymbolService.class))
                .withBean(AppProperties.class, AppProperties::new)
                .withBean(SetupScanService.class)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
