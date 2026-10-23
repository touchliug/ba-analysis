package com.ba.analyzer.controller;

import com.ba.analyzer.analysis.Analyzer;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.scheduler.AnalysisScheduler;
import com.ba.analyzer.service.SymbolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分析功能Web API控制器
 * 提供以下REST接口 (enabled 配置只控制定时批次, 手动触发不受限)：
 * - GET  /api/analysis/list          列出所有分析器名称
 * - GET  /api/analysis/run/{name}    异步执行指定分析器，立即返回任务状态
 * - POST /api/analysis/sync/{name}   同步执行指定分析器，支持query参数和JSON body两种传参方式
 * - GET  /api/analysis/latest        获取所有分析器的最新分析结果
 * - GET  /api/analysis/latest/{name} 获取指定分析器的最新分析结果
 * - GET  /api/analysis/symbols       获取合约列表 (每次从MySQL实时读取)
 * - GET  /api/analysis/params/{name} 获取指定分析器支持的参数说明
 */
@Slf4j
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private static final long RATE_LIMIT_MS = 10_000L;

    private final List<Analyzer> analyzers;
    private final AnalysisScheduler analysisScheduler;
    private final SymbolService symbolService;
    private final Map<String, AtomicLong> lastSyncTime = new ConcurrentHashMap<>();

    @GetMapping("/list")
    public ResponseEntity<List<String>> listAnalyzers() {
        List<String> names = analyzers.stream()
                .map(Analyzer::getName)
                .toList();
        return ResponseEntity.ok(names);
    }

    @GetMapping("/run/{name}")
    public ResponseEntity<Map<String, Object>> runAnalysis(@PathVariable String name) {
        Analyzer analyzer = analyzers.stream()
                .filter(a -> a.getName().equals(name))
                .findFirst()
                .orElse(null);

        if (analyzer == null) {
            return ResponseEntity.notFound().build();
        }

        // enabled 开关只控制定时批次是否自动运行; 手动触发(/run 与 /sync)对禁用的分析器同样放行,
        // 两个端点语义一致 —— 此前 /run 拦禁用而 /sync 不拦, 前端实际一直在"绕过"该检查。
        if (analysisScheduler.isRunning(name)) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "running");
            result.put("analyzer", name);
            result.put("message", "分析器正在执行中，请稍后通过 /latest/" + name + " 查询结果");
            return ResponseEntity.ok(result);
        }

        AtomicLong lastTime = lastSyncTime.computeIfAbsent(name, k -> new AtomicLong(0));
        long now = System.currentTimeMillis();
        if (now - lastTime.get() < RATE_LIMIT_MS) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "rate_limited");
            result.put("analyzer", name);
            result.put("retryAfter", (RATE_LIMIT_MS - (now - lastTime.get())) / 1000);
            result.put("message", "请求过于频繁，请稍后重试");
            return ResponseEntity.status(429).body(result);
        }
        lastTime.set(now);

        analysisScheduler.runAsync(name, analyzer);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "started");
        result.put("analyzer", name);
        result.put("message", "分析已启动，请通过 /api/analysis/latest/" + name + " 查询结果");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/sync/{name}")
    public ResponseEntity<AnalysisReport> syncAnalysis(
            @PathVariable String name,
            @RequestBody(required = false) Map<String, Object> bodyParams,
            @RequestParam(required = false) Map<String, String> queryParams) {

        Analyzer analyzer = analyzers.stream()
                .filter(a -> a.getName().equals(name))
                .findFirst()
                .orElse(null);

        if (analyzer == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> params = mergeParams(bodyParams, queryParams);
        log.info("Sync run: {}, bodyParams: {}, queryParams: {}, mergedParams: {}",
                analyzer.getName(), bodyParams, queryParams, params);

        AtomicLong lastTime = lastSyncTime.computeIfAbsent(name, k -> new AtomicLong(0));
        long now = System.currentTimeMillis();
        if (now - lastTime.get() < RATE_LIMIT_MS) {
            return ResponseEntity.status(429).body(null);
        }
        lastTime.set(now);

        try {
            List<String> symbols = symbolService.getSymbolNames();
            AnalysisReport report = analyzer.analyze(symbols, params);
            analysisScheduler.getLatestReports().put(report.getAnalysisType(), report);
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("Sync run failed: {}", name, e);
            return ResponseEntity.internalServerError().body(null);
        }
    }

    @GetMapping("/params/{name}")
    public ResponseEntity<Map<String, Object>> getAnalyzerParams(@PathVariable String name) {
        Analyzer analyzer = analyzers.stream()
                .filter(a -> a.getName().equals(name))
                .findFirst()
                .orElse(null);

        if (analyzer == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("name", analyzer.getName());
        result.put("description", analyzer.getDescription());
        result.put("enabled", analyzer.isEnabled());
        result.put("params", analyzer.getParamSpec());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/latest")
    public ResponseEntity<Map<String, AnalysisReport>> getLatestReports() {
        return ResponseEntity.ok(analysisScheduler.getLatestReports());
    }

    @GetMapping("/latest/{name}")
    public ResponseEntity<AnalysisReport> getLatestReport(@PathVariable String name) {
        AnalysisReport report = analysisScheduler.getLatestReports().get(name);
        if (report == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(report);
    }

    @GetMapping("/symbols")
    public ResponseEntity<List<String>> getSymbols() {
        return ResponseEntity.ok(symbolService.getSymbolNames());
    }

    private Map<String, Object> mergeParams(Map<String, Object> bodyParams, Map<String, String> queryParams) {
        Map<String, Object> merged = new HashMap<>();
        if (queryParams != null && !queryParams.isEmpty()) {
            queryParams.forEach((k, v) -> {
                if (v == null) return;
                try {
                    if (v.contains(".")) {
                        merged.put(k, Double.parseDouble(v));
                    } else {
                        merged.put(k, Integer.parseInt(v));
                    }
                } catch (NumberFormatException e) {
                    merged.put(k, v);
                }
            });
        }
        if (bodyParams != null && !bodyParams.isEmpty()) {
            merged.putAll(bodyParams);
        }
        return merged;
    }
}
