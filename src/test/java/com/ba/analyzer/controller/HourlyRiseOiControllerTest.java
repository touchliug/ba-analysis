package com.ba.analyzer.controller;

import com.ba.analyzer.service.HourlyRiseOiService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HourlyRiseOiControllerTest {

    private static HourlyRiseOiService.ScanResult empty(int periods) {
        return new HourlyRiseOiService.ScanResult(System.currentTimeMillis(), 522, 0, periods, 0, List.of());
    }

    @Test
    void clampsPeriodsAndPassesRefresh() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        when(svc.getLatest(2, true)).thenReturn(empty(2));
        when(svc.getLatest(24, false)).thenReturn(empty(24));
        HourlyRiseOiController controller = new HourlyRiseOiController(svc);

        ResponseEntity<?> lo = controller.hourlyRise(1, true);
        ResponseEntity<?> hi = controller.hourlyRise(999, false);

        assertEquals(200, lo.getStatusCode().value());
        assertEquals(200, hi.getStatusCode().value());
        verify(svc).getLatest(2, true);
        verify(svc).getLatest(24, false);
    }

    @Test
    void returnsEmptyResultNotError() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        when(svc.getLatest(4, false)).thenReturn(empty(4));

        ResponseEntity<?> resp = new HourlyRiseOiController(svc).hourlyRise(4, false);

        assertEquals(200, resp.getStatusCode().value());
        HourlyRiseOiService.ScanResult body = (HourlyRiseOiService.ScanResult) resp.getBody();
        assertNotNull(body);
        assertTrue(body.matches().isEmpty());
    }

    @Test
    void returns503WhenScanBusy() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        when(svc.getLatest(4, true)).thenThrow(new HourlyRiseOiService.ScanBusyException());

        ResponseEntity<?> resp = new HourlyRiseOiController(svc).hourlyRise(4, true);

        assertEquals(503, resp.getStatusCode().value());
    }

    @Test
    void returns500WhenScanFails() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        when(svc.getLatest(4, false)).thenThrow(new IllegalStateException("db down"));

        ResponseEntity<?> resp = new HourlyRiseOiController(svc).hourlyRise(4, false);

        assertEquals(500, resp.getStatusCode().value());
    }
}
