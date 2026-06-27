package com.ba.analyzer.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotModelsTest {

    @Test
    void ticker24hNumericGetters() {
        Ticker24hData t = new Ticker24hData();
        t.setPriceChangePercent("7.623");
        t.setLastPrice("123.45");
        t.setQuoteVolume("9876543.21");
        assertEquals(7.623, t.getPriceChangePercentNum(), 1e-9);
        assertEquals(123.45, t.getLastPriceNum(), 1e-9);
        assertEquals(9876543.21, t.getQuoteVolumeNum(), 1e-6);
        // blank/invalid -> 0
        Ticker24hData empty = new Ticker24hData();
        assertEquals(0.0, empty.getPriceChangePercentNum(), 1e-9);
    }

    @Test
    void premiumIndexNumericGetters() {
        PremiumIndexData p = new PremiumIndexData();
        p.setMarkPrice("100.5");
        p.setLastFundingRate("-0.00012345");
        assertEquals(100.5, p.getMarkPriceNum(), 1e-9);
        assertEquals(-0.00012345, p.getLastFundingRateNum(), 1e-12);
        PremiumIndexData empty = new PremiumIndexData();
        assertEquals(0.0, empty.getLastFundingRateNum(), 1e-12);
    }
}
