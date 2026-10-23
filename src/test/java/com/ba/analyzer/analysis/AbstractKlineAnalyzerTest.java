package com.ba.analyzer.analysis;

import com.ba.analyzer.model.KlineData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AbstractKlineAnalyzerTest {

    private static final long DAY = 86_400_000L;

    private static KlineData k(long closeTime) {
        KlineData kd = new KlineData();
        kd.setCloseTime(closeTime);
        return kd;
    }

    @Test
    void dropsUnclosedLastCandle() {
        long now = System.currentTimeMillis();
        var result = AbstractKlineAnalyzer.toClosedCandles(
                Map.of("A", List.of(k(now - DAY - 1000), k(now - 1000), k(now + DAY))), now);
        assertEquals(2, result.get("A").size());
    }

    @Test
    void keepsAllWhenLastIsClosed() {
        long now = System.currentTimeMillis();
        var result = AbstractKlineAnalyzer.toClosedCandles(
                Map.of("A", List.of(k(now - DAY - 1000), k(now - 1000))), now);
        assertEquals(2, result.get("A").size());
    }

    @Test
    void skipsStaleSymbol() {
        long now = System.currentTimeMillis();
        var result = AbstractKlineAnalyzer.toClosedCandles(
                Map.of("A", List.of(k(now - 3 * DAY), k(now - 2 * DAY))), now);
        assertFalse(result.containsKey("A"));
    }

    @Test
    void unknownCloseTimeTreatedAsClosed() {
        var result = AbstractKlineAnalyzer.toClosedCandles(
                Map.of("A", List.of(k(0), k(0))), System.currentTimeMillis());
        assertEquals(2, result.get("A").size());
    }

    @Test
    void skipsSymbolWithOnlyUnclosedCandle() {
        long now = System.currentTimeMillis();
        var result = AbstractKlineAnalyzer.toClosedCandles(Map.of("A", List.of(k(now + DAY))), now);
        assertFalse(result.containsKey("A"));
    }
}
