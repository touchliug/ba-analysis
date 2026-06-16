package com.ba.analyzer.report;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Component
public class ReportWriter {

    private static final Map<String, String> NAME_MAPPING = Map.of(
            "连续上涨", "consecutive_rise",
            "连涨今跌", "rise_then_drop",
            "大幅上涨", "high_rise",
            "连跌今涨", "drop_then_rise",
            "缓涨币", "slow_rise",
            "价涨仓增", "price_drop_oi_rise",
            "低位插针", "low_price_consolidation",
            "短期急涨", "short_term_rise",
            "底部吸筹", "bullish_accumulation"
    );

    private static final int MAX_REPORT_KEEP_DAYS = 30;

    private final AppProperties appProperties;

    public ReportWriter(AppProperties appProperties) {
        this.appProperties = appProperties;
        cleanOldReports();
    }

    private void cleanOldReports() {
        try {
            Path reportRoot = Path.of(appProperties.getReport().getDir());
            if (!Files.exists(reportRoot)) return;

            java.time.LocalDate cutoff = java.time.LocalDate.now().minusDays(MAX_REPORT_KEEP_DAYS);
            DateTimeFormatter dirFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");

            Files.list(reportRoot).forEach(p -> {
                try {
                    if (Files.isDirectory(p)) {
                        java.time.LocalDate dirDate = java.time.LocalDate.parse(
                                p.getFileName().toString(), dirFormatter);
                        if (dirDate.isBefore(cutoff)) {
                            deleteDirectory(p.toFile());
                            log.info("Cleaned old report directory: {}", p);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception e) {
            log.warn("Failed to clean old reports", e);
        }
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    public void writeReport(AnalysisReport report) {
        String dir = appProperties.getReport().getDir();
        String dateStr = report.getAnalysisTime()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String timeStr = report.getAnalysisTime()
                .format(DateTimeFormatter.ofPattern("HHmmss"));

        String fileSafeName = NAME_MAPPING.getOrDefault(report.getAnalysisType(),
                report.getAnalysisType().replaceAll("[^a-zA-Z0-9_-]", "_"));

        String fileName = String.format("%s_%s_%s.txt",
                dateStr, timeStr, fileSafeName);

        try {
            Path reportDir = Path.of(dir, dateStr);
            Files.createDirectories(reportDir);

            String textContent = formatTextReport(report);
            Path textPath = reportDir.resolve(fileName);
            Files.writeString(textPath, textContent);

            log.info("Report written: {} ({} coins matched)", textPath, report.getMatchedCount());
        } catch (IOException e) {
            log.error("Failed to write report for: {}", report.getAnalysisType(), e);
        }
    }

    private String formatTextReport(AnalysisReport report) {
        StringBuilder sb = new StringBuilder();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        sb.append("=".repeat(60)).append("\n");
        sb.append("分析类型: ").append(report.getAnalysisType()).append("\n");
        sb.append("分析描述: ").append(report.getDescription()).append("\n");
        sb.append("分析时间: ").append(report.getAnalysisTime().format(dtf)).append("\n");
        sb.append("分析总数: ").append(report.getTotalAnalyzed()).append("\n");
        sb.append("匹配数量: ").append(report.getMatchedCount()).append("\n");
        sb.append("=".repeat(60)).append("\n\n");

        if (report.getCoins().isEmpty()) {
            sb.append("无匹配结果\n");
        } else {
            sb.append(String.format("%-20s %-15s %-15s %s%n",
                    "交易对", "当前价格", "涨跌幅%", "详情"));
            sb.append("-".repeat(80)).append("\n");

            for (AnalysisReport.CoinAnalysis coin : report.getCoins()) {
                sb.append(String.format("%-20s %-15.4f %-15.2f %s%n",
                        coin.getSymbol(),
                        coin.getCurrentPrice(),
                        coin.getChangePercent(),
                        coin.getDetail()));
            }
        }

        sb.append("\n").append("=".repeat(60)).append("\n");
        return sb.toString();
    }
}
