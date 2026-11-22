package com.ba.analyzer.scheduler;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.service.HourlyRiseOiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每小时预热「小时连涨 + OI 净增」缓存。
 *
 * <p>放在整点后 10 分钟：ba-sync 的 1h OI 在 :05/:35 入库，留 5 分钟余量。
 * 扫描失败不动上一次快照，页面继续读旧结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HourlyRiseOiScheduler {

    private final HourlyRiseOiService hourlyRiseOiService;
    private final AppProperties appProperties;

    @Scheduled(cron = "${binance.schedule.hourly-scan}")
    public void runHourlyScan() {
        if (!appProperties.getHourlyScan().isEnabled()) return;
        int periods = appProperties.getHourlyScan().getPeriods();
        try {
            HourlyRiseOiService.ScanResult r = hourlyRiseOiService.scan(periods);
            log.info("hourly-rise-oi scheduled scan done: periods={} matched={}", periods, r.matchedCount());
        } catch (Exception e) {
            log.error("hourly-rise-oi scheduled scan failed, keeping previous snapshot", e);
        }
    }
}
