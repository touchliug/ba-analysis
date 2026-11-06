package com.ba.analyzer.service;

import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataFetchServiceTest {

    @Mock
    private JdbcDataStore dataStore;

    @InjectMocks
    private DataFetchService dataFetchService;

    @Test
    void reusesCachedKlinesWhenSmallerWindowIsRequested() {
        when(dataStore.getKlinesBatch(anyList(), eq("1d"), anyInt(), anyLong()))
                .thenReturn(Map.of("BTCUSDT", klines(6)));

        Map<String, List<KlineData>> first = dataFetchService.fetchDailyKlines(List.of("BTCUSDT"), 5);
        Map<String, List<KlineData>> second = dataFetchService.fetchDailyKlines(List.of("BTCUSDT"), 3);

        assertEquals(5, first.get("BTCUSDT").size());
        assertEquals(3, second.get("BTCUSDT").size());
        verify(dataStore, times(1)).getKlinesBatch(anyList(), eq("1d"), anyInt(), anyLong());
    }

    @Test
    void refetchesWhenSymbolUniverseChanges() {
        when(dataStore.getKlinesBatch(anyList(), eq("1d"), anyInt(), anyLong()))
                .thenReturn(Map.of("BTCUSDT", klines(6)))
                .thenReturn(Map.of("ETHUSDT", klines(6)));

        dataFetchService.fetchDailyKlines(List.of("BTCUSDT"), 5);
        dataFetchService.fetchDailyKlines(List.of("ETHUSDT"), 5);

        verify(dataStore, times(2)).getKlinesBatch(anyList(), eq("1d"), anyInt(), anyLong());
    }

    @Test
    void keepsDailyAndHourlyCachesSeparate() {
        when(dataStore.getKlinesBatch(anyList(), eq("1d"), anyInt(), anyLong()))
                .thenReturn(Map.of("BTCUSDT", klines(6)));
        when(dataStore.getKlinesBatch(anyList(), eq("1h"), anyInt(), anyLong()))
                .thenReturn(Map.of("BTCUSDT", klines(6)));

        dataFetchService.fetchKlines(List.of("BTCUSDT"), "1d", 5);
        dataFetchService.fetchKlines(List.of("BTCUSDT"), "1h", 5);
        dataFetchService.fetchKlines(List.of("BTCUSDT"), "1d", 5);
        dataFetchService.fetchKlines(List.of("BTCUSDT"), "1h", 5);

        verify(dataStore, times(1)).getKlinesBatch(anyList(), eq("1d"), anyInt(), anyLong());
        verify(dataStore, times(1)).getKlinesBatch(anyList(), eq("1h"), anyInt(), anyLong());
    }

    @Test
    void readsOpenInterestForRequestedPeriod() {
        OpenInterestData oi = new OpenInterestData();
        oi.setSymbol("BTCUSDT");
        oi.setSumOpenInterest("100");
        when(dataStore.getOpenInterestBatch(anyList(), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", List.of(oi)));

        Map<String, List<OpenInterestData>> result =
                dataFetchService.fetchOpenInterestHistoryBatch(List.of("BTCUSDT"), "1h", 6);

        assertEquals(1, result.get("BTCUSDT").size());
        verify(dataStore).getOpenInterestBatch(anyList(), eq("1h"), anyInt());
    }

    private static List<KlineData> klines(int count) {
        long day = 86_400_000L;
        List<KlineData> klines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            KlineData k = new KlineData();
            k.setOpenTime(i * day);
            k.setCloseTime((i + 1) * day - 1);
            k.setOpen("10");
            k.setHigh("11");
            k.setLow("9");
            k.setClose("10.5");
            k.setQuoteAssetVolume("1000");
            klines.add(k);
        }
        return klines;
    }
}
