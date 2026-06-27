package com.ba.analyzer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SymbolServiceTest {

    @Mock
    private JdbcDataStore dataStore;

    @InjectMocks
    private SymbolService symbolService;

    @Test
    void usesSymbolsTableWhenAvailable() {
        when(dataStore.getTradingSymbolNames()).thenReturn(List.of("BTCUSDT", "ETHUSDT"));
        List<String> result = symbolService.getSymbolNames();
        assertEquals(List.of("BTCUSDT", "ETHUSDT"), result);
        verify(dataStore, never()).getSymbolNamesFromKlines();
    }

    @Test
    void fallsBackToKlinesWhenSymbolsEmpty() {
        when(dataStore.getTradingSymbolNames()).thenReturn(List.of());
        when(dataStore.getSymbolNamesFromKlines()).thenReturn(List.of("SOLUSDT"));
        List<String> result = symbolService.getSymbolNames();
        assertEquals(List.of("SOLUSDT"), result);
    }

    @Test
    void returnsEmptyWhenBothEmpty() {
        when(dataStore.getTradingSymbolNames()).thenReturn(List.of());
        when(dataStore.getSymbolNamesFromKlines()).thenReturn(List.of());
        assertTrue(symbolService.getSymbolNames().isEmpty());
    }
}
