package com.ba.analyzer.scheduler;

import com.ba.analyzer.analysis.Analyzer;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.report.ReportWriter;
import com.ba.analyzer.service.SymbolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisSchedulerTest {

    @Mock
    private SymbolService symbolService;
    @Mock
    private ReportWriter reportWriter;
    @Mock
    private Analyzer analyzer;

    private ExecutorService executor;
    private AnalysisScheduler scheduler;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        scheduler = new AnalysisScheduler(symbolService, reportWriter, List.of(analyzer), executor);
        when(analyzer.analyze(anyList())).thenReturn(AnalysisReport.builder()
                .analysisType("test")
                .analysisTime(LocalDateTime.now())
                .coins(List.of())
                .build());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void refreshesSymbolsOnEveryManualRun() {
        when(symbolService.getSymbolNames())
                .thenReturn(List.of("BTCUSDT"))
                .thenReturn(List.of("BTCUSDT", "ETHUSDT"));

        scheduler.runAsync("test", analyzer);
        scheduler.runAsync("test", analyzer);

        verify(symbolService, timeout(2000).times(2)).getSymbolNames();
    }
}
