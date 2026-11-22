package com.ba.analyzer.scheduler;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.service.SetupScanService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SetupScanSchedulerTest {

    @Test
    void skipsWhenDisabled() {
        SetupScanService svc = mock(SetupScanService.class);
        AppProperties props = new AppProperties();
        props.getSetupScan().setEnabled(false);

        new SetupScanScheduler(svc, props).runSetupScan();

        verifyNoInteractions(svc);
    }

    @Test
    void scansAllCapturers() {
        SetupScanService svc = mock(SetupScanService.class);
        when(svc.scanAll()).thenReturn(Map.of());
        AppProperties props = new AppProperties();
        props.getSetupScan().setEnabled(true);

        new SetupScanScheduler(svc, props).runSetupScan();

        verify(svc).scanAll();
    }

    @Test
    void swallowsScanFailure() {
        SetupScanService svc = mock(SetupScanService.class);
        when(svc.scanAll()).thenThrow(new IllegalStateException("boom"));

        new SetupScanScheduler(svc, new AppProperties()).runSetupScan();   // 不抛异常

        verify(svc).scanAll();
    }
}
