package com.ba.analyzer.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LongShortRatioDataTest {

    @Test
    void numericGettersParseStrings() {
        LongShortRatioData d = new LongShortRatioData();
        d.setLongShortRatio("1.85");
        d.setLongValue("0.65");
        d.setShortValue("0.35");
        assertEquals(1.85, d.getRatioValue(), 1e-9);
        assertEquals(0.65, d.getLongValueNum(), 1e-9);
        assertEquals(0.35, d.getShortValueNum(), 1e-9);
    }

    @Test
    void numericGettersReturnZeroOnNullOrBlank() {
        LongShortRatioData d = new LongShortRatioData();
        assertEquals(0.0, d.getRatioValue(), 1e-9);
        d.setLongShortRatio("");
        assertEquals(0.0, d.getRatioValue(), 1e-9);
        d.setLongShortRatio("not-a-number");
        assertEquals(0.0, d.getRatioValue(), 1e-9);
    }
}
