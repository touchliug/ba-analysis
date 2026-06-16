package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NDayLowAnalyzer implements Analyzer {

    private final DataFetchService dataFetchService;
    private final AppProperties appProperties;

    @Override
    public String getName() {
        return "N日最低";
    }

    @Override
    public String getDescription() {
        int days = appProperties.getAnalysis().getNDayLow().getDays();
        return String.format("获取所有合约最近%d天的最低价, 标记豹子号和整数关口", days);
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getNDayLow().isEnabled();
    }

    @Override
    public AnalysisReport analyze(List<String> symbols) {
        int days = appProperties.getAnalysis().getNDayLow().getDays();
        Map<String, Object> usedParams = new LinkedHashMap<>();
        usedParams.put("days", days);
        usedParams.put("source", "config");
        return doAnalyze(symbols, days, usedParams);
    }

    @Override
    public AnalysisReport analyze(List<String> symbols, Map<String, Object> params) {
        int defaultDays = appProperties.getAnalysis().getNDayLow().getDays();
        int days = getIntParam(params, "days", defaultDays);
        Map<String, Object> usedParams = new LinkedHashMap<>();
        usedParams.put("days", days);
        usedParams.put("source", params != null && params.containsKey("days") ? "request" : "config");
        return doAnalyze(symbols, days, usedParams);
    }

    private AnalysisReport doAnalyze(List<String> symbols, int days, Map<String, Object> usedParams) {
        Map<String, List<KlineData>> klineMap = dataFetchService.fetchDailyKlines(symbols, days + 1);
        long now = System.currentTimeMillis();
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> klines = entry.getValue();
            if (klines.isEmpty()) continue;

            List<KlineData> closed = new ArrayList<>(klines);
            if (!closed.isEmpty() && closed.get(closed.size() - 1).getCloseTime() > now) {
                closed.remove(closed.size() - 1);
            }
            if (closed.isEmpty()) continue;

            List<KlineData> recent = closed.size() > days
                    ? closed.subList(closed.size() - days, closed.size())
                    : closed;

            // Find min low price and its date
            double minPrice = Double.MAX_VALUE;
            long minPriceTime = 0;
            double maxPrice = 0;
            for (KlineData k : recent) {
                if (k.getLowPrice() < minPrice) {
                    minPrice = k.getLowPrice();
                    minPriceTime = k.getOpenTime();
                }
                if (k.getHighPrice() > maxPrice) maxPrice = k.getHighPrice();
            }
            if (minPrice == Double.MAX_VALUE) minPrice = 0;

            double lastClose = recent.get(recent.size() - 1).getClosePrice();
            double fromLow = minPrice > 0 ? (lastClose - minPrice) / minPrice * 100 : 0;

            // Format low date
            String lowDate = "";
            if (minPriceTime > 0) {
                java.time.LocalDateTime dt = java.time.LocalDateTime.ofEpochSecond(
                        minPriceTime / 1000, 0, java.time.ZoneOffset.ofHours(8));
                lowDate = dt.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"));
            }

            // Detect patterns on both min low and current price
            String minPriceStr = formatPrice(minPrice);
            String curPriceStr = formatPrice(lastClose);
            boolean isLeopard = isLeopardNumber(minPriceStr) || isLeopardNumber(curPriceStr);
            boolean isInteger = isIntegerPrice(minPrice) || isIntegerPrice(lastClose);
            boolean hasTag = isLeopard || isInteger;

            String tags = "";
            if (isLeopard) tags += "[豹子号]";
            if (isInteger) tags += "[整数关口]";

            String detail = String.format("%s%s日最低%.8f(%s) 现价%.8f 距低点%+.1f%%",
                    tags.isEmpty() ? "" : tags + " ",
                    days, minPrice, lowDate, lastClose, fromLow);

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .currentPrice(lastClose)
                    .changePercent(fromLow)
                    .detail(detail)
                    .build());
        }

        // Sort: 豹子号/整数 first, then by symbol
        matched.sort(Comparator
                .comparing((AnalysisReport.CoinAnalysis c) -> {
                    String d = c.getDetail();
                    if (d != null && (d.contains("[豹子号]") || d.contains("[整数关口]"))) return 0;
                    return 1;
                })
                .thenComparing(AnalysisReport.CoinAnalysis::getSymbol));

        return AnalysisReport.builder()
                .analysisType(getName())
                .analysisTime(LocalDateTime.now())
                .description(String.format("所有合约最近%d天(不含今日)的最低价, 标记豹子号和整数关口", days))
                .coins(matched)
                .totalAnalyzed(symbols.size())
                .matchedCount(matched.size())
                .usedParams(usedParams)
                .build();
    }

    /**
     * Detect "豹子号" — decimal part has 4+ consecutive same non-zero digits
     * after stripping leading zeros. Avoids false positives like 0.000111.
     * Examples: 0.1111 → true, 0.33333 → true, 0.000111 → false (only 3 sig digits)
     */
    static boolean isLeopardNumber(String priceStr) {
        int dotIdx = priceStr.indexOf('.');
        if (dotIdx < 0) return false;
        String decimal = priceStr.substring(dotIdx + 1);
        // Strip leading zeros — they're just placeholders, not豹子
        String stripped = decimal.replaceFirst("^0+", "");
        if (stripped.length() < 4) return false;

        // Check for 4+ consecutive same digits (must be non-zero)
        int run = 1;
        for (int i = 1; i < stripped.length(); i++) {
            if (stripped.charAt(i) == stripped.charAt(i - 1) && stripped.charAt(i) != '0') {
                run++;
                if (run >= 4) return true;
            } else {
                run = 1;
            }
        }
        return false;
    }

    /**
     * Detect "整数关口" — price is within 0.05% of a whole number.
     * Examples: 1.0001 → true, 0.9999 → true, 5.000 → true
     */
    static boolean isIntegerPrice(double price) {
        if (price <= 0) return false;
        double rounded = Math.round(price);
        double deviation = Math.abs(price - rounded) / price;
        return deviation < 0.0005; // within 0.05%
    }

    /**
     * Format price to a string with up to 8 decimal places,
     * trimming trailing zeros but keeping at least some precision.
     */
    static String formatPrice(double price) {
        if (price >= 1) {
            return String.format("%.8f", price).replaceAll("0+$", "").replaceAll("\\.$", "");
        } else {
            // For sub-1 prices, keep more precision
            return String.format("%.8f", price);
        }
    }
}
