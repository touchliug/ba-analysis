package com.ba.analyzer.controller;

import com.ba.analyzer.service.HourlyRiseOiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 小时连涨 + OI 净增扫描（独立页面，不挂在分析器框架下）。
 *
 * <p>与 {@link MarketController} 同路径前缀但独立实现：它需要「扫描 + 缓存」语义，
 * 不适合塞进纯查询的 MarketController。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class HourlyRiseOiController {

    private final HourlyRiseOiService hourlyRiseOiService;

    @GetMapping(value = "/api/market/hourly-rise-oi", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> hourlyRise(
            @RequestParam(defaultValue = "4") int periods,
            @RequestParam(defaultValue = "false") boolean refresh) {
        int n = HourlyRiseOiService.clampPeriods(periods);
        try {
            return ResponseEntity.ok(hourlyRiseOiService.getLatest(n, refresh));
        } catch (HourlyRiseOiService.ScanBusyException e) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "scan in progress", "periods", n));
        } catch (Exception e) {
            log.error("hourly-rise-oi scan failed: periods={}", n, e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "scan failed", "periods", n));
        }
    }
}
