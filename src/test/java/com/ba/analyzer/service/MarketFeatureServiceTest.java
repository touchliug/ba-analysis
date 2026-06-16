package com.ba.analyzer.service;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.MarketFeature;
import com.ba.analyzer.model.OpenInterestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class MarketFeatureServiceTest {

    @Mock
    private DataFetchService dataFetchService;
    @Mock
    private AppProperties appProperties;

    private MarketFeatureService service;

    @BeforeEach
    void setUp() {
        service = new MarketFeatureService(dataFetchService, appProperties);
        // 资金费率默认无数据 → 0(中性)
        lenient().when(dataFetchService.getRecentFundingRates(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
    }

    private KlineData k(double close, double vol) {
        KlineData kd = new KlineData();
        kd.setOpen(String.valueOf(close));
        kd.setHigh(String.valueOf(close * 1.01));
        kd.setLow(String.valueOf(close * 0.99));
        kd.setClose(String.valueOf(close));
        kd.setVolume(String.valueOf(vol));
        kd.setQuoteAssetVolume(String.valueOf(vol));
        return kd;
    }

    private OpenInterestData oi(double value, long ts) {
        OpenInterestData o = new OpenInterestData();
        o.setSumOpenInterest(String.valueOf(value));
        o.setTimestamp(ts);
        return o;
    }

    /** 288根5m K线: 前半价格100, 近6h(后72根)涨到110, 且近6h成交量放大。 */
    private List<KlineData> build5mKlines() {
        List<KlineData> list = new ArrayList<>();
        for (int i = 0; i < 288; i++) {
            boolean recent6h = i >= 288 - 72;
            double price = recent6h ? 110 : 100;
            double vol = recent6h ? 300 : 100; // 近6h放量3x
            list.add(k(price, vol));
        }
        return list;
    }

    /** 288根5m OI: 6h前为1000, 近6h升到1180 (+18%), 末根不塌。 */
    private List<OpenInterestData> build5mOi() {
        List<OpenInterestData> list = new ArrayList<>();
        for (int i = 0; i < 288; i++) {
            double v = i >= 288 - 72 ? 1180 : 1000;
            list.add(oi(v, i));
        }
        return list;
    }

    @Test
    void computesOiAndPriceWindows() {
        MarketFeature f = service.computeOne("TESTUSDT", build5mKlines(), build5mOi(), null);
        assertNotNull(f);
        assertTrue(f.isOiAvailable());
        // 6h OI: 1000→1180 = +18%
        assertEquals(18.0, f.getOiChangeH6(), 0.5);
        // 6h价格: 100→110 = +10%
        assertEquals(10.0, f.getPriceChangeH6(), 0.5);
        // 量能: 近6h/前6h = 3x
        assertEquals(3.0, f.getVolChangeH6(), 0.1);
    }

    @Test
    void detectsContractIgnition() {
        // 30d OI低位 (传入1d OI: 从2000跌到1180, d30为负)
        List<OpenInterestData> oi1d = List.of(oi(2000, 0), oi(1180, 1));
        MarketFeature f = service.computeOne("TESTUSDT", build5mKlines(), build5mOi(), oi1d);
        assertNotNull(f);
        // 6h OI +18% >= 8 阈值, 5m不塌, 30d低位 → 点火
        assertTrue(f.isContractIgnition(), "应识别为合约点火");
        assertTrue(f.isVolumeRelay(), "近6h放量3x应判为量能接力");
    }

    @Test
    void oiUnavailableWhenInsufficient() {
        // 只给少量OI(<72根) → oiAvailable=false
        List<OpenInterestData> shortOi = List.of(oi(1000, 0), oi(1010, 1));
        MarketFeature f = service.computeOne("TESTUSDT", build5mKlines(), shortOi, null);
        assertNotNull(f);
        assertFalse(f.isOiAvailable());
        assertFalse(f.isContractIgnition(), "OI不可用时不应点火");
    }

    @Test
    void nullKlinesReturnsNull() {
        assertNull(service.computeOne("X", null, null, null));
    }
}
