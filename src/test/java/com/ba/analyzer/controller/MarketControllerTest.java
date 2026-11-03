package com.ba.analyzer.controller;

import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.service.JdbcDataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketControllerTest {

    @Mock
    private JdbcDataStore store;

    @InjectMocks
    private MarketController controller;

    private LongShortRatioData lsr(String symbol, double ratio) {
        LongShortRatioData d = new LongShortRatioData();
        d.setSymbol(symbol);
        d.setLongShortRatio(String.valueOf(ratio));
        return d;
    }

    @Test
    void lsrRankingSortsDescAndLimits() {
        when(store.getLatestLongShortRatioAll("TOP_POSITION", "1h"))
                .thenReturn(new java.util.ArrayList<>(List.of(
                        lsr("A", 1.0), lsr("B", 3.0), lsr("C", 2.0))));
        ResponseEntity<?> resp = controller.lsrRanking("TOP_POSITION", "1h", 2);
        assertEquals(200, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<LongShortRatioData> body = (List<LongShortRatioData>) resp.getBody();
        assertEquals(2, body.size());
        assertEquals("B", body.get(0).getSymbol());
        assertEquals("C", body.get(1).getSymbol());
    }

    @Test
    void lsrRankingRejectsInvalidType() {
        ResponseEntity<?> resp = controller.lsrRanking("BOGUS", "1h", 50);
        assertEquals(400, resp.getStatusCode().value());
        verify(store, never()).getLatestLongShortRatioAll(any(), any());
    }

    @Test
    void lsrBySymbolReturnsAllFourTypes() {
        when(store.getLongShortRatio(eq("BTCUSDT"), anyString(), eq("1h"), anyInt()))
                .thenReturn(List.of(lsr("BTCUSDT", 1.5)));
        ResponseEntity<Map<String, List<LongShortRatioData>>> resp =
                controller.lsr("BTCUSDT", "1h", 48);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(4, resp.getBody().size());
        assertTrue(resp.getBody().containsKey("TOP_POSITION"));
    }

    private com.ba.analyzer.model.Ticker24hData ticker(String symbol, double chgPct, double quoteVol) {
        com.ba.analyzer.model.Ticker24hData t = new com.ba.analyzer.model.Ticker24hData();
        t.setSymbol(symbol);
        t.setPriceChangePercent(String.valueOf(chgPct));
        t.setQuoteVolume(String.valueOf(quoteVol));
        return t;
    }

    private com.ba.analyzer.model.PremiumIndexData premium(String symbol, double fundingRate) {
        com.ba.analyzer.model.PremiumIndexData p = new com.ba.analyzer.model.PremiumIndexData();
        p.setSymbol(symbol);
        p.setLastFundingRate(String.valueOf(fundingRate));
        return p;
    }

    @Test
    void tickerGainersSortedDescByChangePct() {
        when(store.getAllTicker24h()).thenReturn(new java.util.ArrayList<>(List.of(
                ticker("A", 1.0, 10), ticker("B", 9.0, 10), ticker("C", 5.0, 10))));
        ResponseEntity<List<com.ba.analyzer.model.Ticker24hData>> resp = controller.tickerGainers(2);
        assertEquals("B", resp.getBody().get(0).getSymbol());
        assertEquals("C", resp.getBody().get(1).getSymbol());
        assertEquals(2, resp.getBody().size());
    }

    @Test
    void tickerLosersSortedAscByChangePct() {
        when(store.getAllTicker24h()).thenReturn(new java.util.ArrayList<>(List.of(
                ticker("A", 1.0, 10), ticker("B", -9.0, 10), ticker("C", 5.0, 10))));
        ResponseEntity<List<com.ba.analyzer.model.Ticker24hData>> resp = controller.tickerLosers(1);
        assertEquals("B", resp.getBody().get(0).getSymbol());
        assertEquals(1, resp.getBody().size());
    }

    @Test
    void tickerVolumeSortedDescByQuoteVolume() {
        when(store.getAllTicker24h()).thenReturn(new java.util.ArrayList<>(List.of(
                ticker("A", 1.0, 100), ticker("B", 1.0, 999), ticker("C", 1.0, 50))));
        ResponseEntity<List<com.ba.analyzer.model.Ticker24hData>> resp = controller.tickerVolume(2);
        assertEquals("B", resp.getBody().get(0).getSymbol());
    }

    @Test
    void tickerBySymbol404WhenMissing() {
        when(store.getTicker24h("NOPEUSDT")).thenReturn(null);
        ResponseEntity<com.ba.analyzer.model.Ticker24hData> resp = controller.ticker("NOPEUSDT");
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void fundingRankingDescByFundingRate() {
        when(store.getAllPremiumIndex()).thenReturn(new java.util.ArrayList<>(List.of(
                premium("A", 0.0001), premium("B", 0.0009), premium("C", -0.0003))));
        ResponseEntity<List<com.ba.analyzer.model.PremiumIndexData>> resp =
                controller.fundingRanking(2, "desc");
        assertEquals("B", resp.getBody().get(0).getSymbol());
        assertEquals("A", resp.getBody().get(1).getSymbol());
    }

    @Test
    void fundingRankingAscByFundingRate() {
        when(store.getAllPremiumIndex()).thenReturn(new java.util.ArrayList<>(List.of(
                premium("A", 0.0001), premium("B", 0.0009), premium("C", -0.0003))));
        ResponseEntity<List<com.ba.analyzer.model.PremiumIndexData>> resp =
                controller.fundingRanking(1, "asc");
        assertEquals("C", resp.getBody().get(0).getSymbol());
    }

    @Test
    void limitClampedToAtLeastOne() {
        when(store.getAllTicker24h()).thenReturn(new java.util.ArrayList<>(List.of(
                ticker("A", 1.0, 10), ticker("B", 9.0, 10))));
        ResponseEntity<List<com.ba.analyzer.model.Ticker24hData>> resp = controller.tickerGainers(0);
        assertEquals(1, resp.getBody().size()); // limit 0 -> clamped to 1
    }

    // ---- flow: 价格 / 主动买占比 / OI 三合一 ----

    private com.ba.analyzer.model.KlineData kline(long openTime, String close, String quoteVol, String takerBuy) {
        com.ba.analyzer.model.KlineData k = new com.ba.analyzer.model.KlineData();
        k.setOpenTime(openTime);
        k.setClose(close);
        k.setQuoteAssetVolume(quoteVol);
        k.setTakerBuyQuoteAssetVolume(takerBuy);
        return k;
    }

    private com.ba.analyzer.model.OpenInterestData oi(long ts, String coin, String usd) {
        com.ba.analyzer.model.OpenInterestData o = new com.ba.analyzer.model.OpenInterestData();
        o.setTimestamp(ts);
        o.setSumOpenInterest(coin);
        o.setSumOpenInterestValue(usd);
        return o;
    }

    @Test
    @SuppressWarnings("unchecked")
    void flowMergesPriceTakerOiAndTopPosition() {
        long t = 1_700_000_000_000L;
        when(store.getKlines("XUSDT", "1h", 10))
                .thenReturn(List.of(kline(t, "0.5", "1000", "600")));
        // OI / LSR 落在同一小时桶内(差 7 分钟), 仍应匹配上
        when(store.getOpenInterestHistory("XUSDT", "1h", 10))
                .thenReturn(List.of(oi(t + 7 * 60_000L, "123", "456")));
        LongShortRatioData ratio = lsr("XUSDT", 1.8);
        ratio.setTimestamp(t + 3 * 60_000L);
        when(store.getLongShortRatio("XUSDT", "TOP_POSITION", "1h", 10))
                .thenReturn(List.of(ratio));

        ResponseEntity<Map<String, Object>> resp = controller.flow("XUSDT", "1h", 10);
        assertEquals(200, resp.getStatusCode().value());

        List<Map<String, Object>> points = (List<Map<String, Object>>) resp.getBody().get("points");
        assertEquals(1, points.size());
        Map<String, Object> p = points.get(0);
        assertEquals(0.5, (Double) p.get("close"), 1e-9);
        assertEquals(60.0, (Double) p.get("buyPct"), 1e-9);
        assertEquals(123.0, (Double) p.get("oiCoin"), 1e-9);
        assertEquals(456.0, (Double) p.get("oiUsd"), 1e-9);
        assertEquals(1.8, (Double) p.get("topPos"), 1e-9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void flowToleratesMissingOiAndZeroVolume() {
        long t = 1_700_003_600_000L;
        when(store.getKlines("XUSDT", "1h", 5))
                .thenReturn(List.of(kline(t, "1.2", "0", "0")));
        when(store.getOpenInterestHistory("XUSDT", "1h", 5)).thenReturn(List.of());
        when(store.getLongShortRatio("XUSDT", "TOP_POSITION", "1h", 5)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> resp = controller.flow("XUSDT", "1h", 5);
        Map<String, Object> p = ((List<Map<String, Object>>) resp.getBody().get("points")).get(0);
        assertNull(p.get("buyPct"));   // 成交额为 0 -> 不除零, 返回 null
        assertNull(p.get("oiCoin"));   // OI 未同步 -> null, 前端跳过绘制
        assertNull(p.get("oiUsd"));
        assertNull(p.get("topPos"));   // 多空比未同步 -> null
        assertEquals(1.2, (Double) p.get("close"), 1e-9);
    }
}
