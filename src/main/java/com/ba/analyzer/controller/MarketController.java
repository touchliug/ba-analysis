package com.ba.analyzer.controller;

import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.model.PremiumIndexData;
import com.ba.analyzer.model.Ticker24hData;
import com.ba.analyzer.service.JdbcDataStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行情查询/快照接口 (只读, 无分析、无定时、无速率限制)。
 * - 多空比: long_short_ratio
 * - 24h行情快照: ticker_24h
 * - 标记价/资金费快照: premium_index
 */
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketController {

    private static final Set<String> RATIO_TYPES =
            Set.of("GLOBAL_ACCOUNT", "TOP_ACCOUNT", "TOP_POSITION", "TAKER");
    private static final int MAX_LIMIT = 500;

    private final JdbcDataStore store;

    private static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    // ---- 多空比 ----

    @GetMapping("/lsr/{symbol}")
    public ResponseEntity<Map<String, List<LongShortRatioData>>> lsr(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String period,
            @RequestParam(defaultValue = "48") int limit) {
        int lim = clampLimit(limit);
        Map<String, List<LongShortRatioData>> out = new LinkedHashMap<>();
        for (String type : RATIO_TYPES) {
            out.put(type, store.getLongShortRatio(symbol, type, period, lim));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/lsr/ranking")
    public ResponseEntity<?> lsrRanking(
            @RequestParam String type,
            @RequestParam(defaultValue = "1h") String period,
            @RequestParam(defaultValue = "50") int limit) {
        if (!RATIO_TYPES.contains(type)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid type", "allowed", RATIO_TYPES));
        }
        int lim = clampLimit(limit);
        List<LongShortRatioData> latest = store.getLatestLongShortRatioAll(type, period);
        latest.sort(Comparator.comparingDouble(LongShortRatioData::getRatioValue).reversed());
        return ResponseEntity.ok(latest.stream().limit(lim).toList());
    }
}
