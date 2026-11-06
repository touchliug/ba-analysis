package com.ba.analyzer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenerServiceTest {

    @Mock
    private StageService stageService;

    @InjectMocks
    private ScreenerService screenerService;

    @Test
    void oiScreenKeepsOnlyMovesAboveEightPercent() {
        when(stageService.load()).thenReturn(Map.of(
                "BIGUSDT", daysWithLastOiChange(0.20),
                "FLATUSDT", daysWithLastOiChange(0.01)));

        List<ScreenerService.Row> rows = screenerService.screen("oi", 50);

        assertEquals(1, rows.size());
        assertEquals("BIGUSDT", rows.get(0).symbol());
        assertTrue(rows.get(0).oiChg1() > 0.08);
    }

    private static List<StageService.Day> daysWithLastOiChange(double change) {
        long day = 86_400_000L;
        List<StageService.Day> days = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            double oi = i == 44 ? 100 * (1 + change) : 100;
            days.add(new StageService.Day(
                    i * day, 10, 10.5, 9.5, 1000, oi, 0, 1, 1));
        }
        return days;
    }
}
