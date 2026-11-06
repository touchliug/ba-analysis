package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连续扫描分析器基类。
 *
 * <p>三个子类共用 interval(1d/1h) + periods(默认5) 参数、已收盘过滤和报告构建。
 * 价格类按 close/open 判断，OI 类由子类自行取数。
 */
public abstract class AbstractStreakAnalyzer implements Analyzer {

    protected static final long DAY_MS = 86_400_000L;
    protected static final long HOUR_MS = 3_600_000L;
    /** 多取一些数据，用于报告实际连续根数（可能长于 periods）。 */
    protected static final int EXTRA_PERIODS = 20;

    protected final DataFetchService dataFetchService;
    protected final AppProperties appProperties;

    protected AbstractStreakAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        this.dataFetchService = dataFetchService;
        this.appProperties = appProperties;
    }

    @Override
    public AnalysisReport analyze(List<String> symbols) {
        Map<String, Object> usedParams = getDefaultParams();
        usedParams.put("source", "config");
        return executeAnalysis(symbols, usedParams);
    }

    @Override
    public AnalysisReport analyze(List<String> symbols, Map<String, Object> params) {
        Map<String, Object> usedParams = resolveParams(params);
        usedParams.put("source", "request");
        return executeAnalysis(symbols, usedParams);
    }

    protected AnalysisReport executeAnalysis(List<String> symbols, Map<String, Object> params) {
        List<AnalysisReport.CoinAnalysis> matched = doAnalyze(symbols, params);
        sortResults(matched);
        return AnalysisReport.builder()
                .analysisType(getName())
                .analysisTime(LocalDateTime.now())
                .description(buildDescription(params))
                .coins(matched)
                .totalAnalyzed(symbols.size())
                .matchedCount(matched.size())
                .usedParams(params)
                .build();
    }

    protected abstract List<AnalysisReport.CoinAnalysis> doAnalyze(
            List<String> symbols, Map<String, Object> params);

    protected abstract Map<String, Object> getDefaultParams();

    protected abstract Map<String, Object> resolveParams(Map<String, Object> requestParams);

    protected abstract String getConfiguredInterval();

    protected abstract int getConfiguredPeriods();

    protected abstract String buildDescription(Map<String, Object> params);

    /** 周期为 1h 时进入 short-term 批次；1d 进入 daily 批次。 */
    @Override
    public boolean requiresIntradayData() {
        return "1h".equals(configuredInterval());
    }

    @Override
    public Map<String, Object> getParamSpec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("interval", Analyzer.selectParam(
                "周期: 1d=天, 1h=小时", configuredInterval(), List.of("1d", "1h")));
        spec.put("periods", Analyzer.param("int", "连续根数", normalizedConfiguredPeriods()));
        return spec;
    }

    protected String configuredInterval() {
        return normalizeInterval(getConfiguredInterval(), "1d");
    }

    protected int normalizedConfiguredPeriods() {
        return normalizePeriods(getConfiguredPeriods(), 5);
    }

    protected void sortResults(List<AnalysisReport.CoinAnalysis> matched) {
        matched.sort(Comparator.comparingDouble(AnalysisReport.CoinAnalysis::getChangePercent).reversed());
    }

    protected static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    protected static String normalizeInterval(Object value, String defaultInterval) {
        if (value == null) return "1h".equals(defaultInterval) ? "1h" : "1d";
        String interval = value.toString().trim().toLowerCase();
        return ("1d".equals(interval) || "1h".equals(interval)) ? interval : defaultInterval;
    }

    protected static int normalizePeriods(Object value, int defaultPeriods) {
        int periods = defaultPeriods;
        if (value instanceof Number n) {
            periods = n.intValue();
        } else if (value != null) {
            try {
                periods = Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        return Math.max(1, Math.min(500, periods));
    }

    protected static String intervalLabel(String interval) {
        return "1h".equals(interval) ? "小时" : "日";
    }

    protected static long maxStalenessMs(String interval) {
        return "1h".equals(interval) ? 2 * HOUR_MS : DAY_MS;
    }

    /**
     * OI 的日线同步任务在 08:10 北京运行，而 daily-analysis 在 08:05 运行，
     * 因此 08:05 时最新的 1d OI 点通常已是前一天 00:00 UTC，必须放宽到 2 天。
     */
    protected static long maxOiStalenessMs(String interval) {
        return "1h".equals(interval) ? 2 * HOUR_MS : 2 * DAY_MS;
    }

    /** 去掉未收盘根，并跳过同步滞后超过一个周期的币。 */
    protected static Map<String, List<KlineData>> closedCandles(
            Map<String, List<KlineData>> klineMap, String interval, long now) {
        long maxStale = maxStalenessMs(interval);
        Map<String, List<KlineData>> result = new HashMap<>();
        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            List<KlineData> klines = entry.getValue();
            if (klines == null || klines.isEmpty()) continue;
            int end = klines.size();
            if (klines.get(end - 1).getCloseTime() > now) end--;
            if (end == 0) continue;
            long lastClose = klines.get(end - 1).getCloseTime();
            if (lastClose > 0 && now - lastClose > maxStale) continue;
            result.put(entry.getKey(), klines.subList(0, end));
        }
        return result;
    }

    protected static boolean isBullish(KlineData k) {
        return k.getClosePrice() > k.getOpenPrice();
    }

    protected static boolean isBearish(KlineData k) {
        return k.getClosePrice() < k.getOpenPrice();
    }

    protected static String dateOf(long timestamp, String interval) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(
                "1h".equals(interval) ? "MM-dd HH:mm" : "MM-dd");
        return Instant.ofEpochMilli(timestamp).atOffset(ZoneOffset.ofHours(8)).format(fmt);
    }

    protected static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    protected static String streakLabel(int run, int dataSize) {
        return run >= dataSize ? "≥" + run : String.valueOf(run);
    }

    protected static List<AnalysisReport.CoinAnalysis> newMatches() {
        return new ArrayList<>();
    }
}
