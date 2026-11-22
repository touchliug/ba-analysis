package com.ba.analyzer.scheduler;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.service.SetupScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 每小时预热「启动扫描」四个缓存槽。
 *
 * <p>放在整点后 40 分钟：与 hourly-scan（:10）错开，且 ba-sync 的 1h OI 在 :05/:35 入库。
 * 扫描失败不动上一次快照，页面继续读旧结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SetupScanScheduler {

    private final SetupScanService setupScanService;
    private final AppProperties appProperties;

    @Scheduled(cron = "${binance.schedule.setup-scan:0 40 * * * ?}")
    public void runSetupScan() {
        if (!appProperties.getSetupScan().isEnabled()) return;
        try {
            Map<String, SetupScanService.ScanResult> r = setupScanService.scanAll();
            log.info("setup-scan scheduled done: spike={} flag={} pullback={} retailShort={}",
                    count(r, SetupScanService.TYPE_VOLUME_SPIKE),
                    count(r, SetupScanService.TYPE_FLAG_BREAKOUT),
                    count(r, SetupScanService.TYPE_PULLBACK_CONFIRM),
                    count(r, SetupScanService.TYPE_RETAIL_SHORT));
        } catch (Exception e) {
            log.error("setup-scan scheduled scan failed, keeping previous snapshot", e);
        }
    }

    private static int count(Map<String, SetupScanService.ScanResult> r, String type) {
        SetupScanService.ScanResult s = r.get(type);
        return s == null ? 0 : s.matchedCount();
    }
}
