package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连续上涨 —— 首阴日候选池。
 *
 * 找出截至最近一根已收盘日K仍在连涨的强势币: 这些币一旦收阴, 就可能触发首阴日做空信号。
 * 满足首阴日连涨天数与累涨门槛的标记为 [首阴候选]。
 * 已合并原"缓涨币": maxDailyRisePercent=3 + maxVolumeRatio<1 即温和缩量连涨。
 *
 * 只用已收盘K线: 当前未收盘的日K(closeTime 在未来)不参与判断。
 */
@Slf4j
@Component
public class ConsecutiveRiseAnalyzer extends AbstractKlineAnalyzer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM-dd");

    public ConsecutiveRiseAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "连续上涨";
    }

    @Override
    public String getDescription() {
        return buildDescription(getDefaultParams());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getConsecutiveRise().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var cfg = appProperties.getAnalysis().getConsecutiveRise();
        return createParams(
                "days", cfg.getDays(),
                "minDailyRisePercent", cfg.getMinDailyRisePercent(),
                "maxDailyRisePercent", cfg.getMaxDailyRisePercent(),
                "minTotalRisePercent", cfg.getMinTotalRisePercent(),
                "volumeSurgeRatio", cfg.getVolumeSurgeRatio(),
                "maxVolumeRatio", cfg.getMaxVolumeRatio());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var cfg = appProperties.getAnalysis().getConsecutiveRise();
        return createParams(
                "days", getIntParam(requestParams, "days", cfg.getDays()),
                "minDailyRisePercent", getDoubleParam(requestParams, "minDailyRisePercent", cfg.getMinDailyRisePercent()),
                "maxDailyRisePercent", getDoubleParam(requestParams, "maxDailyRisePercent", cfg.getMaxDailyRisePercent()),
                "minTotalRisePercent", getDoubleParam(requestParams, "minTotalRisePercent", cfg.getMinTotalRisePercent()),
                "volumeSurgeRatio", getDoubleParam(requestParams, "volumeSurgeRatio", cfg.getVolumeSurgeRatio()),
                "maxVolumeRatio", getDoubleParam(requestParams, "maxVolumeRatio", cfg.getMaxVolumeRatio()));
    }

    @Override
    public Map<String, Object> getParamSpec() {
        var c = appProperties.getAnalysis().getConsecutiveRise();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("days", Analyzer.param("int", "最少连续阳线天数", c.getDays()));
        s.put("minDailyRisePercent", Analyzer.param("double", "单根阳线最小实体涨幅%(过滤十字星/微动)", c.getMinDailyRisePercent()));
        s.put("maxDailyRisePercent", Analyzer.param("double", "单根阳线最大实体涨幅%(0=不限; 设3即温和缓涨)", c.getMaxDailyRisePercent()));
        s.put("minTotalRisePercent", Analyzer.param("double", "累计涨幅下限%(复利, 0=不过滤)", c.getMinTotalRisePercent()));
        s.put("volumeSurgeRatio", Analyzer.param("double", "量比下限(连涨期均额/之前均额, 1.0=不过滤)", c.getVolumeSurgeRatio()));
        s.put("maxVolumeRatio", Analyzer.param("double", "量比上限(0=不限; 设<1即缩量连涨)", c.getMaxVolumeRatio()));
        return s;
    }

    @Override
    protected Map<String, List<KlineData>> fetchData(List<String> symbols, Map<String, Object> params) {
        int days = (int) params.get("days");
        // 连涨区间 + 基准根 + 当前未收盘根; 缓冲用于识别更长连涨及计算之前的均量。
        return dataFetchService.fetchDailyKlines(symbols, days + 2 + getHistoryBufferDays());
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        double minTotal = (double) params.get("minTotalRisePercent");
        return String.format("首阴日候选池: 截至最近收盘连涨≥%d天(每天≥%.1f%%%s)的强势币, 收阴即可能触发首阴做空",
                (int) params.get("days"), (double) params.get("minDailyRisePercent"),
                minTotal > 0 ? String.format(", 累涨≥%.0f%%", minTotal) : "");
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        double minDailyRise = (double) params.get("minDailyRisePercent");
        double maxDailyRise = (double) params.get("maxDailyRisePercent");
        double minTotalRise = (double) params.get("minTotalRisePercent");
        double volumeSurgeRatio = (double) params.get("volumeSurgeRatio");
        double maxVolumeRatio = (double) params.get("maxVolumeRatio");

        var yinCfg = appProperties.getAnalysis().getFirstYinDay();
        long now = System.currentTimeMillis();
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.isEmpty()) continue;

            int lastIdx = klines.size() - 1;
            if (klines.get(lastIdx).getCloseTime() > now) lastIdx--;
            if (lastIdx < 0) continue;

            KlineData last = klines.get(lastIdx);
            // 日线同步滞后时最新已收盘根不是"昨天" → 跳过, 避免把旧行情当成仍在连涨
            if (last.getCloseTime() > 0 && now - last.getCloseTime() > DAY_MS) continue;

            int runStart = lastIdx + 1;
            while (runStart > 0 && isValidRise(klines.get(runStart - 1), minDailyRise, maxDailyRise)) runStart--;
            int runDays = lastIdx - runStart + 1;
            if (runDays < days) continue;
            // 连涨一直延伸到取数窗口起点: 实际天数可能更长
            boolean runAtWindowStart = runStart == 0;

            double refPrice = runStart > 0
                    ? klines.get(runStart - 1).getClosePrice()
                    : klines.get(runStart).getOpenPrice();
            if (refPrice <= 0) continue;
            double totalRise = (last.getClosePrice() / refPrice - 1) * 100;
            if (minTotalRise > 0 && totalRise < minTotalRise) continue;

            double runAvgVol = avgQuoteVolume(klines, runStart, lastIdx + 1);
            double histAvgVol = avgQuoteVolume(klines, 0, runStart);
            boolean hasHistory = histAvgVol > 0;
            double volRatio = hasHistory ? runAvgVol / histAvgVol : 0;
            if (volumeSurgeRatio > 1.0 && (!hasHistory || volRatio < volumeSurgeRatio)) continue;
            if (maxVolumeRatio > 0 && (!hasHistory || volRatio > maxVolumeRatio)) continue;

            boolean yinCandidate = runDays >= yinCfg.getDays() && totalRise >= yinCfg.getMinTotalRisePercent();
            String lastDate = Instant.ofEpochMilli(last.getOpenTime())
                    .atOffset(ZoneOffset.ofHours(8)).format(DATE_FMT);

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(last.getClosePrice())
                    .changePercent(totalRise)
                    .detail(String.format("%s连涨%s%d天 累涨%.2f%% 日均%.2f%% %s | 截至%s收盘",
                            yinCandidate ? "[首阴候选] " : "",
                            runAtWindowStart ? "≥" : "", runDays, totalRise,
                            totalRise / runDays,
                            hasHistory ? String.format("量比%.1fx", volRatio) : "量比-(无历史)",
                            lastDate))
                    .build());
        }
        return matched;
    }

    private static boolean isValidRise(KlineData k, double minDailyRise, double maxDailyRise) {
        double body = k.getChangePercent();
        return k.getClosePrice() > k.getOpenPrice() && body >= minDailyRise
                && (maxDailyRise <= 0 || body <= maxDailyRise);
    }

    private static double avgQuoteVolume(List<KlineData> klines, int from, int to) {
        if (to <= from) return 0;
        double sum = 0;
        for (int i = from; i < to; i++) sum += klines.get(i).getQuoteVolume();
        return sum / (to - from);
    }
}
