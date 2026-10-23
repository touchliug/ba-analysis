package com.ba.analyzer.controller;

import com.ba.analyzer.analysis.Analyzer;
import com.ba.analyzer.config.AppProperties;
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
    private final AppProperties appProperties;
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
        result.put("params", getParamSpec(name));
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

    private Map<String, Object> getParamSpec(String name) {
        Map<String, Object> params = new HashMap<>();
        return switch (name) {
            case "连续上涨" -> {
                params.put("days", Map.of("type", "int", "description", "连续上涨天数", "default", 3));
                yield params;
            }
            case "连涨今跌" -> {
                params.put("days", Map.of("type", "int", "description", "连续上涨天数", "default", 3));
                yield params;
            }
            case "大幅上涨" -> {
                params.put("days", Map.of("type", "int", "description", "统计天数", "default", 7));
                params.put("thresholdPercent", Map.of("type", "double", "description", "涨幅阈值百分比", "default", 50));
                yield params;
            }
            case "连跌今涨" -> {
                params.put("days", Map.of("type", "int", "description", "连续下跌天数", "default", 5));
                yield params;
            }
            case "缓涨币" -> {
                params.put("days", Map.of("type", "int", "description", "统计天数", "default", 7));
                params.put("maxDailyChangePercent", Map.of("type", "double", "description", "每日最大涨幅百分比", "default", 3));
                yield params;
            }
            case "价涨仓增" -> {
                params.put("days", Map.of("type", "int", "description", "统计天数", "default", 5));
                params.put("maxPriceRisePercent", Map.of("type", "double", "description", "最大价格上涨百分比", "default", 10));
                params.put("minOiRisePercent", Map.of("type", "double", "description", "最小持仓量增长百分比", "default", 20));
                yield params;
            }
            case "低位插针" -> {
                params.put("days", Map.of("type", "int", "description", "统计天数", "default", 7));
                params.put("maxPriceChangePercent", Map.of("type", "double", "description", "最大价格变化百分比", "default", 10));
                params.put("lowPricePercentile", Map.of("type", "double", "description", "低价分位数(0-100)", "default", 30));
                yield params;
            }
            case "首阴日" -> {
                var c = appProperties.getAnalysis().getFirstYinDay();
                params.put("days", Map.of("type", "int", "description", "首阴前最少连续阳线天数", "default", c.getDays()));
                params.put("minTotalRisePercent", Map.of("type", "double", "description", "连涨累计涨幅下限%(复利)", "default", c.getMinTotalRisePercent()));
                params.put("minYinDropPercent", Map.of("type", "double", "description", "首阴相对昨收最小跌幅%", "default", c.getMinYinDropPercent()));
                params.put("volumeSurgeRatio", Map.of("type", "double", "description", "首阴量比下限(成交额/连涨期均额, 1.0=不过滤)", "default", c.getVolumeSurgeRatio()));
                params.put("takeProfitPct", Map.of("type", "double", "description", "止盈: 入场价下方%", "default", c.getTakeProfitPct()));
                params.put("stopBufferPct", Map.of("type", "double", "description", "止损: 冲高最高价上方缓冲%", "default", c.getStopBufferPct()));
                params.put("maxStopLossPct", Map.of("type", "double", "description", "入场到止损距离上限%, 超出不输出(0=不限)", "default", c.getMaxStopLossPct()));
                params.put("minScore", Map.of("type", "int", "description", "见顶强度分最低分(0-100, 0=不过滤)", "default", c.getMinScore()));
                yield params;
            }
            case "底部吸筹" -> {
                params.put("days", Map.of("type", "int", "description", "分析窗口(天)", "default", 14));
                params.put("minScore", Map.of("type", "int", "description", "最低吸筹评分", "default", 60));
                params.put("priceLookbackDays", Map.of("type", "int", "description", "价格位置回溯天数", "default", 30));
                params.put("drawdownMin", Map.of("type", "double", "description", "最低回撤%", "default", 10.0));
                params.put("requireHigherLow", Map.of("type", "boolean", "description", "要求低点抬高", "default", true));
                params.put("volumeIncreaseMin", Map.of("type", "double", "description", "量能回升最小倍数", "default", 1.2));
                params.put("rangeContractionMax", Map.of("type", "double", "description", "振幅收敛最大比例", "default", 0.8));
                yield params;
            }
            case "N日最低" -> {
                params.put("days", Map.of("type", "int", "description", "统计天数", "default", 7));
                yield params;
            }
            case "反转做多" -> {
                params.put("declineMinDays", Map.of("type", "int", "description", "最少连续下跌天数", "default", 4));
                params.put("declineMinPct", Map.of("type", "double", "description", "累计跌幅下限%", "default", 5.0));
                params.put("declineMaxPct", Map.of("type", "double", "description", "累计跌幅上限%", "default", 12.0));
                params.put("reversalMinPct", Map.of("type", "double", "description", "反转日涨幅下限%", "default", 1.0));
                params.put("reversalMaxPct", Map.of("type", "double", "description", "反转日涨幅上限%", "default", 4.0));
                params.put("takeProfitPct", Map.of("type", "double", "description", "止盈点位%", "default", 6.0));
                params.put("stopLossPct", Map.of("type", "double", "description", "止损点位%", "default", 4.0));
                params.put("volumeConfirmRatio", Map.of("type", "double", "description", "反转日量比阈值(1.0=不过滤)", "default", 1.0));
                params.put("qualityMinScore", Map.of("type", "int", "description", "反转质量分最低分(0-100,0=不过滤;回测建议55)", "default", 0));
                yield params;
            }
            case "山寨币暴涨预警" -> {
                params.put("days", Map.of("type", "int", "description", "分析周期(天)", "default", 7));
                params.put("minScore", Map.of("type", "int", "description", "最低预警评分", "default", 65));
                params.put("maxPriceDeclinePercent", Map.of("type", "double", "description", "最大价格跌幅%(防接飞刀)", "default", 15.0));
                params.put("oiConsecutiveDays", Map.of("type", "int", "description", "OI连续上涨最少天数", "default", 3));
                params.put("volumeExpansionRatio", Map.of("type", "double", "description", "量能扩张倍数阈值", "default", 1.5));
                params.put("lowPricePercentile", Map.of("type", "double", "description", "低价分位数阈值", "default", 35.0));
                params.put("wickBodyRatio", Map.of("type", "double", "description", "下影线/实体最小倍数", "default", 1.5));
                params.put("silentBuyerRatio", Map.of("type", "double", "description", "吃货比(阳量/阴量)阈值", "default", 1.3));
                params.put("maxDrawdownPercent", Map.of("type", "double", "description", "窗口最大回撤上限%", "default", 25.0));
                yield params;
            }
            case "多空比异动" -> {
                params.put("period", Map.of("type", "string", "description", "LSR统计周期", "default", "1h"));
                params.put("lookback", Map.of("type", "int", "description", "与多少根之前比较", "default", 12));
                params.put("minScore", Map.of("type", "int", "description", "最低评分(0-100)", "default", 60));
                params.put("takerBullishMin", Map.of("type", "double", "description", "taker买卖比买盘占优阈值", "default", 1.0));
                params.put("_note", Map.of("type", "string", "description", "该分析器参数当前仅从配置(application.yml)读取, 暂不支持按请求覆盖; 此处仅为文档说明"));
                yield params;
            }
            default -> params;
        };
    }
}
