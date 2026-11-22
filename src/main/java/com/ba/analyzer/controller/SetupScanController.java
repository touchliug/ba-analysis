package com.ba.analyzer.controller;

import com.ba.analyzer.service.SetupScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 启动扫描（放量突破 / 高位旗形 / 突破回踩 / 散户空头拥挤）。
 *
 * <p>与 {@link HourlyRiseOiController} 同级：需要「扫描 + 缓存」语义，
 * 四个 type 共用一次全市场加载。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SetupScanController {

    private final SetupScanService setupScanService;

    @GetMapping(value = "/api/market/setup-scan/types", produces = "application/json;charset=UTF-8")
    public List<SetupScanService.Def> types() {
        return SetupScanService.DEFS;
    }

    @GetMapping(value = "/api/market/setup-scan", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> setupScan(
            @RequestParam(defaultValue = SetupScanService.TYPE_VOLUME_SPIKE) String type,
            @RequestParam(defaultValue = "false") boolean refresh) {
        String t;
        try {
            t = SetupScanService.normalizeType(type);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown type", "type", type));
        }
        try {
            return ResponseEntity.ok(setupScanService.getLatest(t, refresh));
        } catch (SetupScanService.ScanBusyException e) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "scan in progress", "type", t));
        } catch (Exception e) {
            log.error("setup scan failed: type={}", t, e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "scan failed", "type", t));
        }
    }
}
