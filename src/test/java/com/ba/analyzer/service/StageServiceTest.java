package com.ba.analyzer.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageServiceTest {

    @Test
    void concurrentColdLoadsOnlyQueryDatabaseOnce() throws Exception {
        BlockingJdbcTemplate jdbc = new BlockingJdbcTemplate();
        StageService service = new StageService(jdbc);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, List<StageService.Day>>> first = pool.submit(service::load);
            Future<Map<String, List<StageService.Day>>> second = pool.submit(service::load);

            assertTrue(jdbc.entered.await(2, TimeUnit.SECONDS), "first load should reach the DB");
            Thread.sleep(200); // give a second, unsynchronized load a chance to also enter
            jdbc.release.countDown();

            assertTrue(first.get(5, TimeUnit.SECONDS).isEmpty());
            assertTrue(second.get(5, TimeUnit.SECONDS).isEmpty());
            assertEquals(1, jdbc.queryForListCalls.get(),
                    "concurrent cold loads must share one DB load");
        } finally {
            pool.shutdownNow();
        }
    }

    /** First call blocks so two unsynchronized loaders can both be observed. */
    private static class BlockingJdbcTemplate extends JdbcTemplate {
        final AtomicInteger queryForListCalls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType) {
            queryForListCalls.incrementAndGet();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }

        @Override
        public void query(String sql, RowCallbackHandler rch, Object... args) {
            // no rows: the test only cares about load coordination
        }
    }
}
