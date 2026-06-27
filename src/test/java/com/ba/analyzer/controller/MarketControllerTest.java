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
}
