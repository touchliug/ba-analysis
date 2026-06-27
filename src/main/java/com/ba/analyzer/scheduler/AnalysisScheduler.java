package com.ba.analyzer.scheduler;

import com.ba.analyzer.analysis.Analyzer;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.report.ReportWriter;
import com.ba.analyzer.service.SymbolService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 分析调度器 (ba-analysis 专用)。
 *
 * 只做分析, 不拉/不写任何数据 —— 数据由独立的同步服务(ba-sync)写入MySQL, 本服务只读。
 * 两个定时任务:
 * 1. daily-analysis     : 每日运行非日内分析器
 * 2. short-term-analysis: 每5分钟运行日内数据分析器
 * 缓存最新分析结果供Web接口查询; 支持异步执行避免Web阻塞。
 *
 * 币种全集每轮分析前从MySQL刷新(SymbolService → symbols 表, klines 去重兜底)。
 */
@Slf4j
@Component
public class AnalysisScheduler {

    private final SymbolService symbolService;
    private final ReportWriter reportWriter;
    private final List<Analyzer> analyzers;
    private final ExecutorService asyncExecutor;

    private final Map<String, AnalysisReport> latestReports = new ConcurrentHashMap<>();
    private final Set<String> runningAnalyzers = ConcurrentHashMap.newKeySet();

    private volatile List<String> cachedSymbols = List.of();

    public AnalysisScheduler(SymbolService symbolService, ReportWriter reportWriter,
                             List<Analyzer> analyzers,
                             @Qualifier("asyncAnalysisExecutor") ExecutorService asyncExecutor) {
        this.symbolService = symbolService;
        this.reportWriter = reportWriter;
        this.analyzers = analyzers;
        this.asyncExecutor = asyncExecutor;
    }

    @Scheduled(cron = "${binance.schedule.daily-analysis}")
    public void runDailyAnalysis() {
        log.info("=== Scheduled: Running daily analysis ===");
        List<String> symbols = refreshSymbols();
        runAnalyzers(symbols, false);
    }

    @Scheduled(cron = "${binance.schedule.short-term-analysis}")
    public void runShortTermAnalysis() {
        log.info("=== Scheduled: Running short-term analysis ===");
        List<String> symbols = refreshSymbols();
        runAnalyzers(symbols, true);
    }

    private void runAnalyzers(List<String> symbols, boolean intraday) {
        for (Analyzer analyzer : analyzers) {
            if (analyzer.requiresIntradayData() != intraday) continue;
            if (!analyzer.isEnabled()) {
                log.info("Analyzer {} is disabled, skipping", analyzer.getName());
                continue;
            }
            try {
                log.info("Running analyzer: {}", analyzer.getName());
                AnalysisReport report = analyzer.analyze(symbols);
                reportWriter.writeReport(report);
                latestReports.put(report.getAnalysisType(), report);
                log.info("Analyzer {} completed: {} matched", analyzer.getName(), report.getMatchedCount());
            } catch (Exception e) {
                log.error("Analyzer {} failed", analyzer.getName(), e);
            }
        }
    }

    public boolean isRunning(String name) {
        return runningAnalyzers.contains(name);
    }

    public void runAsync(String name, Analyzer analyzer) {
        asyncExecutor.submit(() -> {
            runningAnalyzers.add(name);
            try {
                log.info("Async run started: {}", name);
                List<String> symbols = getSymbols();
                AnalysisReport report = analyzer.analyze(symbols);
                reportWriter.writeReport(report);
                latestReports.put(report.getAnalysisType(), report);
                log.info("Async run completed: {}, matched: {}", name, report.getMatchedCount());
            } catch (Exception e) {
                log.error("Async run failed: {}", name, e);
            } finally {
                runningAnalyzers.remove(name);
            }
        });
    }

    public Map<String, AnalysisReport> getLatestReports() {
        return latestReports;
    }

    /** 每轮分析前从MySQL重新拉取币种全集(同步服务可能新增/下架了合约)。 */
    private List<String> refreshSymbols() {
        List<String> symbols = symbolService.getSymbolNames();
        if (!symbols.isEmpty()) cachedSymbols = symbols;
        return cachedSymbols;
    }

    private List<String> getSymbols() {
        if (cachedSymbols.isEmpty()) cachedSymbols = symbolService.getSymbolNames();
        return cachedSymbols;
    }
}
