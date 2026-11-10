package com.ba.analyzer.scheduler;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.service.HourlyRiseOiService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HourlyRiseOiSchedulerTest {

    @Test
    void skipsWhenDisabled() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        AppProperties props = new AppProperties();
        props.getHourlyScan().setEnabled(false);

        new HourlyRiseOiScheduler(svc, props).runHourlyScan();

        verifyNoInteractions(svc);
    }

    @Test
    void scansConfiguredPeriods() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        AppProperties props = new AppProperties();
        props.getHourlyScan().setEnabled(true);
        props.getHourlyScan().setPeriods(5);

        new HourlyRiseOiScheduler(svc, props).runHourlyScan();

        verify(svc).scan(5);
    }

    @Test
    void swallowsScanFailure() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        when(svc.scan(4)).thenThrow(new IllegalStateException("boom"));
        AppProperties props = new AppProperties();

        new HourlyRiseOiScheduler(svc, props).runHourlyScan();   // 不抛异常

        verify(svc).scan(4);
    }
}
