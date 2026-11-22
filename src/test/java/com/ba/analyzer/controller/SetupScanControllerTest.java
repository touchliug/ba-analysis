package com.ba.analyzer.controller;

import com.ba.analyzer.service.SetupScanService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SetupScanControllerTest {

    private static SetupScanService.ScanResult empty(String type) {
        return new SetupScanService.ScanResult(System.currentTimeMillis(), type, 522, 0, List.of());
    }

    @Test
    void passesNormalizedTypeAndRefresh() {
        SetupScanService svc = mock(SetupScanService.class);
        when(svc.getLatest(SetupScanService.TYPE_FLAG_BREAKOUT, true))
                .thenReturn(empty(SetupScanService.TYPE_FLAG_BREAKOUT));
        SetupScanController controller = new SetupScanController(svc);

        ResponseEntity<?> resp = controller.setupScan(" FLAG-BREAKOUT ", true);

        assertEquals(200, resp.getStatusCode().value());
        verify(svc).getLatest(SetupScanService.TYPE_FLAG_BREAKOUT, true);
    }

    @Test
    void defaultsToVolumeSpike() {
        SetupScanService svc = mock(SetupScanService.class);
        when(svc.getLatest(SetupScanService.TYPE_VOLUME_SPIKE, false))
                .thenReturn(empty(SetupScanService.TYPE_VOLUME_SPIKE));

        ResponseEntity<?> resp = new SetupScanController(svc).setupScan(null, false);

        assertEquals(200, resp.getStatusCode().value());
        verify(svc).getLatest(SetupScanService.TYPE_VOLUME_SPIKE, false);
    }

    @Test
    void returns400ForUnknownType() {
        SetupScanService svc = mock(SetupScanService.class);

        ResponseEntity<?> resp = new SetupScanController(svc).setupScan("nope", false);

        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void returns503WhenScanBusy() {
        SetupScanService svc = mock(SetupScanService.class);
        when(svc.getLatest(SetupScanService.TYPE_VOLUME_SPIKE, true))
                .thenThrow(new SetupScanService.ScanBusyException());

        ResponseEntity<?> resp = new SetupScanController(svc).setupScan("volume-spike", true);

        assertEquals(503, resp.getStatusCode().value());
    }

    @Test
    void returns500WhenScanFails() {
        SetupScanService svc = mock(SetupScanService.class);
        when(svc.getLatest(SetupScanService.TYPE_VOLUME_SPIKE, false))
                .thenThrow(new IllegalStateException("db down"));

        ResponseEntity<?> resp = new SetupScanController(svc).setupScan("volume-spike", false);

        assertEquals(500, resp.getStatusCode().value());
    }

    @Test
    void exposesTypeDefinitions() {
        List<SetupScanService.Def> defs = new SetupScanController(mock(SetupScanService.class)).types();

        assertNotNull(defs);
        assertEquals(4, defs.size());
        assertTrue(defs.stream().anyMatch(d -> d.type().equals(SetupScanService.TYPE_PULLBACK_CONFIRM)));
    }
}
