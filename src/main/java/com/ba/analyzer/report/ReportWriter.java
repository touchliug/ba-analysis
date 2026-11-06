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

    private static final Map<String, String> NAME_MAPPING = Map.ofEntries(
            Map.entry("连续上涨", "consecutive_rise"),
            Map.entry("大幅上涨", "high_rise"),
            Map.entry("低位插针", "low_price_consolidation"),
            Map.entry("底部吸筹", "bullish_accumulation"),
            Map.entry("首阴日", "first_yin_day"),
            Map.entry("N日区间", "n_day_range"),
            Map.entry("持仓背离", "oi_divergence"),
            Map.entry("山寨币暴涨预警", "altcoin_pump_alert"),
            Map.entry("反转做多", "reversal_long"),
            Map.entry("多空比异动", "long_short_ratio"),
            Map.entry("价格连续上涨", "price_rise_streak"),
            Map.entry("价格连续下跌", "price_fall_streak"),
            Map.entry("OI连续上涨", "oi_rise_streak")
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
