package com.ba.analyzer.util;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class ExecutorUtils {

    private ExecutorUtils() {
    }

    public static void shutdown(ExecutorService executor, String name) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Executor {} did not terminate gracefully, forcing shutdown", name);
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("Executor {} failed to terminate", name);
                }
            } else {
                log.info("Executor {} shutdown completed", name);
            }
        } catch (InterruptedException e) {
            log.error("Shutdown interrupted for executor {}", name, e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
