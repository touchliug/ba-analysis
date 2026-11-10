package com.ba.analyzer.service;

import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 小时连涨 + OI 净增扫描逻辑。
 *
 * <p>重点锁住三件事：只用已收盘 K 线、只用 sum_open_interest（币数）、OI 端点对不上时如实降级。
 */
class HourlyRiseOiServiceTest {

    private static final long H = 3_600_000L;

    private DataFetchService dataFetch;
    private SymbolService symbols;
    private HourlyRiseOiService service;

    @BeforeEach
    void setUp() {
        dataFetch = mock(DataFetchService.class);
        symbols = mock(SymbolService.class);
        service = new HourlyRiseOiService(dataFetch, symbols);
        when(symbols.getSymbolNames()).thenReturn(List.of("BTCUSDT"));
    }

    /** 收盘时刻 = 开盘 + 1h - 1ms。 */
    private static KlineData kline(long openTime, double open, double close, double quoteVol) {
        KlineData k = new KlineData();
        k.setOpenTime(openTime);
        k.setCloseTime(openTime + H - 1);
        k.setOpen(String.valueOf(open));
        k.setClose(String.valueOf(close));
        k.setQuoteAssetVolume(String.valueOf(quoteVol));
        return k;
    }

    private static OpenInterestData oi(long ts, double sumCoin, double sumValue) {
        OpenInterestData o = new OpenInterestData();
        o.setTimestamp(ts);
        o.setSumOpenInterest(String.valueOf(sumCoin));
        o.setSumOpenInterestValue(String.valueOf(sumValue));
        return o;
    }

    /** 最近 n 根已收盘 1h 线全部收阳，窗口结束时间 = 最新一根的 closeTime。 */
    private static List<KlineData> risingBars(int n, long lastOpenTime) {
        List<KlineData> list = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            long t = lastOpenTime - i * H;
            list.add(kline(t, 100 + (n - 1 - i), 101 + (n - 1 - i), 1_000_000));
        }
        return list;
    }

    private void stubData(List<KlineData> klines, List<OpenInterestData> ois) {
        when(dataFetch.fetchKlines(anyList(), eq("1h"), anyInt())).thenReturn(Map.of("BTCUSDT", klines));
        when(dataFetch.fetchOpenInterestHistoryBatch(anyList(), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", ois));
    }

    @Test
    void matchesRisingBarsWithOiIncrease() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;   // 上一整点
        long start = lastOpen - 3 * H;
        stubData(risingBars(4, lastOpen), List.of(
                oi(start, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

        HourlyRiseOiService.ScanResult r = service.scan(4);

        assertThat(r.matchedCount()).isEqualTo(1);
        assertThat(r.totalSymbols()).isEqualTo(1);
        assertThat(r.skippedNoOi()).isZero();
        HourlyRiseOiService.Match m = r.matches().get(0);
        assertThat(m.symbol()).isEqualTo("BTCUSDT");
        assertThat(m.price()).isEqualTo(104.0);
        assertThat(m.priceChangePercent()).isCloseTo(104.0 / 100.0 - 1, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.oiChangePercent()).isCloseTo(1_100.0 / 1_000.0 - 1, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.windowStart()).isEqualTo(start);
        assertThat(m.oiStartTime()).isEqualTo(start);
        assertThat(m.oiEndTime()).isEqualTo(lastOpen + H);
    }

    @Test
    void usesCoinDenominatedOi() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        long start = lastOpen - 3 * H;
        // 币数净增，但名义值净减（价格下跌）→ 仍应命中
        stubData(risingBars(4, lastOpen), List.of(
                oi(start, 1_000, 10_000_000), oi(lastOpen + H, 1_050, 9_000_000)));

        assertThat(service.scan(4).matchedCount()).isEqualTo(1);
    }

    @Test
    void rejectsWhenOiDecreases() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        long start = lastOpen - 3 * H;
        stubData(risingBars(4, lastOpen), List.of(
                oi(start, 1_100, 6_000), oi(lastOpen + H, 1_000, 5_000)));

        HourlyRiseOiService.ScanResult r = service.scan(4);
        assertThat(r.matchedCount()).isZero();
        assertThat(r.skippedNoOi()).isZero();     // 端点齐全，只是没净增
    }

    @Test
    void rejectsRedBarInWindow() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        long start = lastOpen - 3 * H;
        List<KlineData> bars = risingBars(4, lastOpen);
        bars.set(1, kline(start + H, 102, 101, 1_000_000));   // 中间一根阴线
        stubData(bars, List.of(oi(start, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

        assertThat(service.scan(4).matchedCount()).isZero();
    }

    @Test
    void ignoresUnclosedBar() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        long start = lastOpen - 3 * H;
        List<KlineData> bars = new ArrayList<>(risingBars(4, lastOpen));
        // 未收盘的当前小时：阴线；若被算进窗口就不该命中
        long curOpen = lastOpen + H;
        KlineData openBar = kline(curOpen, 200, 150, 1_000_000);
        openBar.setCloseTime(curOpen + H - 1);     // 明显 > now
        bars.add(openBar);
        stubData(bars, List.of(oi(start, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

        HourlyRiseOiService.ScanResult r = service.scan(4);
        assertThat(r.matchedCount()).isEqualTo(1);
        assertThat(r.matches().get(0).windowEnd()).isEqualTo(lastOpen + H - 1);
    }

    @Test
    void fallsBackToLatestOiWhenEndMissing() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        long start = lastOpen - 3 * H;
        // 只有起点和窗口最后一小时的 OI（窗口结束点还没同步）
        stubData(risingBars(4, lastOpen), List.of(
                oi(start, 1_000, 5_000), oi(lastOpen, 1_050, 5_500)));

        HourlyRiseOiService.Match m = service.scan(4).matches().get(0);
        assertThat(m.oiEndTime()).isEqualTo(lastOpen);       // 退化为窗口内最新点
        assertThat(m.oiChangePercent()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void countsSkippedNoOiWhenStartMissing() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        // 只有窗口结束附近的 OI，起点缺失
        stubData(risingBars(4, lastOpen), List.of(oi(lastOpen + H, 1_100, 6_000)));

        HourlyRiseOiService.ScanResult r = service.scan(4);
        assertThat(r.matchedCount()).isZero();
        assertThat(r.skippedNoOi()).isEqualTo(1);
    }

    @Test
    void skipsWhenKlinesInsufficient() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        stubData(risingBars(2, lastOpen), List.of(
                oi(lastOpen - H, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

        assertThat(service.scan(4).matchedCount()).isZero();
    }

    @Test
    void skipsStaleSymbol() {
        long oldOpen = (System.currentTimeMillis() / H) * H - 10 * H;   // 10 小时前就断了
        stubData(risingBars(4, oldOpen), List.of(
                oi(oldOpen - 3 * H, 1_000, 5_000), oi(oldOpen + H, 1_100, 6_000)));

        assertThat(service.scan(4).matchedCount()).isZero();
    }

    @Test
    void clampsPeriodsToSupportedRange() {
        when(dataFetch.fetchKlines(anyList(), eq("1h"), anyInt())).thenReturn(Map.of());
        when(dataFetch.fetchOpenInterestHistoryBatch(anyList(), eq("1h"), anyInt())).thenReturn(Map.of());

        assertThat(service.scan(1).periods()).isEqualTo(2);
        assertThat(service.scan(99).periods()).isEqualTo(24);
    }

    // ======================== 缓存 / 刷新 / 并发 ========================

    @Test
    void returnsCacheWhenNotRefreshing() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        stubData(risingBars(4, lastOpen), List.of(
                oi(lastOpen - 3 * H, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

        service.getLatest(4, false);
        service.getLatest(4, false);

        verify(dataFetch, times(1)).fetchKlines(anyList(), anyString(), anyInt());
    }

    @Test
    void refreshForcesRescan() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        stubData(risingBars(4, lastOpen), List.of(
                oi(lastOpen - 3 * H, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

        service.getLatest(4, true);
        service.getLatest(4, true);

        verify(dataFetch, times(2)).fetchKlines(anyList(), anyString(), anyInt());
    }

    @Test
    void cacheIsPartitionedByPeriods() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        stubData(risingBars(4, lastOpen), List.of(
                oi(lastOpen - 3 * H, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

        service.getLatest(4, true);
        service.getLatest(5, false);          // 5 槽没有缓存 → 扫一次
        service.getLatest(4, false);          // 4 槽的缓存不能被 5 顶掉

        verify(dataFetch, times(2)).fetchKlines(anyList(), anyString(), anyInt());
        assertThat(service.getLatest(4, false).periods()).isEqualTo(4);
        assertThat(service.getLatest(5, false).periods()).isEqualTo(5);
    }

    @Test
    void concurrentRefreshScansOnce() throws Exception {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        when(dataFetch.fetchKlines(anyList(), anyString(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(300);                // 让两个线程真的撞在一起
            return Map.of("BTCUSDT", risingBars(4, lastOpen));
        });
        when(dataFetch.fetchOpenInterestHistoryBatch(anyList(), anyString(), anyInt()))
                .thenReturn(Map.of("BTCUSDT", List.of(
                        oi(lastOpen - 3 * H, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000))));

        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        Runnable task = () -> {
            try {
                go.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            service.getLatest(4, true);
        };
        Thread a = new Thread(task);
        Thread b = new Thread(task);
        a.start();
        b.start();
        go.countDown();
        a.join();
        b.join();

        verify(dataFetch, times(1)).fetchKlines(anyList(), anyString(), anyInt());
    }
}
