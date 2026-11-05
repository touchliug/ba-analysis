package com.ba.analyzer.controller;

import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.model.OpenInterestData;
import com.ba.analyzer.model.PremiumIndexData;
import com.ba.analyzer.model.Ticker24hData;
import com.ba.analyzer.service.JdbcDataStore;
import com.ba.analyzer.service.ScreenerService;
import com.ba.analyzer.service.StageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    // 固定顺序 (Set.of 迭代序每次JVM启动随机, 用 List 保证 /lsr/{symbol} 响应键序稳定)
    private static final List<String> RATIO_TYPE_ORDER =
            List.of("GLOBAL_ACCOUNT", "TOP_ACCOUNT", "TOP_POSITION", "TAKER");
    private static final Set<String> RATIO_TYPES = Set.copyOf(RATIO_TYPE_ORDER);
    private static final int MAX_LIMIT = 500;

    private final JdbcDataStore store;
    private final StageService stageService;
    private final ScreenerService screenerService;

    private static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    // ---- 多空比 ----

    /** 未知 symbol 返回 200 + 四个空列表 (集合端点语义, 非 404)。 */
    @GetMapping("/lsr/{symbol}")
    public ResponseEntity<Map<String, List<LongShortRatioData>>> lsr(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String period,
            @RequestParam(defaultValue = "48") int limit) {
        int lim = clampLimit(limit);
        Map<String, List<LongShortRatioData>> out = new LinkedHashMap<>();
        for (String type : RATIO_TYPE_ORDER) {
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
        List<LongShortRatioData> ranked = store.getLatestLongShortRatioAll(type, period).stream()
                .sorted(Comparator.comparingDouble(LongShortRatioData::getRatioValue).reversed())
                .limit(lim)
                .toList();
        return ResponseEntity.ok(ranked);
    }

    // ---- 24h 行情快照 ----

    @GetMapping("/ticker/{symbol}")
    public ResponseEntity<Ticker24hData> ticker(@PathVariable String symbol) {
        Ticker24hData t = store.getTicker24h(symbol);
        return t == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(t);
    }

    @GetMapping("/ticker/gainers")
    public ResponseEntity<List<Ticker24hData>> tickerGainers(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(topTickers(
                Comparator.comparingDouble(Ticker24hData::getPriceChangePercentNum).reversed(), limit));
    }

    @GetMapping("/ticker/losers")
    public ResponseEntity<List<Ticker24hData>> tickerLosers(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(topTickers(
                Comparator.comparingDouble(Ticker24hData::getPriceChangePercentNum), limit));
    }

    @GetMapping("/ticker/volume")
    public ResponseEntity<List<Ticker24hData>> tickerVolume(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(topTickers(
                Comparator.comparingDouble(Ticker24hData::getQuoteVolumeNum).reversed(), limit));
    }

    private List<Ticker24hData> topTickers(Comparator<Ticker24hData> cmp, int limit) {
        return store.getAllTicker24h().stream()
                .sorted(cmp)
                .limit(clampLimit(limit))
                .toList();
    }

    // ---- 标记价/资金费快照 ----

    @GetMapping("/premium/{symbol}")
    public ResponseEntity<PremiumIndexData> premium(@PathVariable String symbol) {
        PremiumIndexData p = store.getPremiumIndex(symbol);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }

    @GetMapping("/premium/funding-ranking")
    public ResponseEntity<List<PremiumIndexData>> fundingRanking(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "desc") String order) {
        Comparator<PremiumIndexData> cmp =
                Comparator.comparingDouble(PremiumIndexData::getLastFundingRateNum);
        if (!"asc".equalsIgnoreCase(order)) cmp = cmp.reversed();
        List<PremiumIndexData> ranked = store.getAllPremiumIndex().stream()
                .sorted(cmp)
                .limit(clampLimit(limit))
                .toList();
        return ResponseEntity.ok(ranked);
    }

    // ---- 阶段识别（吸筹 / 试盘 / 拉升 / 派发 / 砸盘 / 冷却） ----

    /**
     * 全市场阶段扫描，按匹配度降序。可选按阶段过滤。
     *
     * <p>注意：这套标签是「状态描述」，回测显示没有预测力（各阶段后续收益与基线差 &lt;±1pp），
     * 页面上不要当成买卖信号。
     */
    @GetMapping(value = "/stage", produces = "application/json;charset=UTF-8")
    public ResponseEntity<List<StageService.Row>> stage(
            @RequestParam(required = false) String stage,
            @RequestParam(defaultValue = "0") int minMatch,
            @RequestParam(defaultValue = "200") int limit) {
        List<StageService.Row> rows = stageService.scan().stream()
                .filter(r -> minMatch <= 0 || r.match() >= minMatch)
                .filter(r -> stage == null || stage.isBlank() || stage.equals(r.stage()))
                .limit(clampLimit(limit))
                .toList();
        return ResponseEntity.ok(rows);
    }

    /** 单币阶段详情：指标 + 证据 + 证伪条件 + 近 90 天收盘价。 */
    @GetMapping("/stage/{symbol}")
    public ResponseEntity<StageService.Detail> stageOne(@PathVariable String symbol) {
        StageService.Detail d = stageService.detail(symbol);
        return d == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(d);
    }

    // ---- 选币筛选 ----

    /** 可用筛选器列表（页面用于渲染标签页）。 */
    @GetMapping(value = "/screen/types", produces = "application/json;charset=UTF-8")
    public ResponseEntity<List<ScreenerService.Def>> screenTypes() {
        return ResponseEntity.ok(ScreenerService.DEFS);
    }

    /**
     * 按筛选器选币。type 见 {@link ScreenerService#DEFS}。
     *
     * <p>所有筛选器共用一套指标（日线 180 天），只做过滤 + 排序，不是买卖信号。
     */
    @GetMapping(value = "/screen", produces = "application/json;charset=UTF-8")
    public ResponseEntity<List<ScreenerService.Row>> screen(
            @RequestParam(defaultValue = "anomaly") String type,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(screenerService.screen(type, clampLimit(limit)));
    }

    // ---- 资金流: 价格 / 主动买占比 / OI / 大户持仓比 四合一 ----

    /**
     * 单币时间序列: 收盘价 + 主动买占比 + OI(币数/名义值) + 大户持仓多空比, 按同一周期对齐。
     * 供前端四线图使用; 未同步到的时点对应字段为 null, 前端跳过绘制。
     * 主动买占比 = taker_buy_quote / quote_asset_volume; 大户持仓比来自 long_short_ratio(TOP_POSITION)。
     */
    @GetMapping("/flow/{symbol}")
    public ResponseEntity<Map<String, Object>> flow(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String period,
            @RequestParam(defaultValue = "72") int limit) {
        int lim = clampLimit(limit);
        long barMs = barMillis(period);

        List<KlineData> klines = store.getKlines(symbol, period, lim);
        // OI / LSR 的时间戳与 K 线 open_time 理论上对齐, 但按周期取整对齐更稳(避免秒级误差导致整行落空)
        Map<Long, OpenInterestData> oiByBucket = new HashMap<>();
        for (OpenInterestData o : store.getOpenInterestHistory(symbol, period, lim)) {
            oiByBucket.put(Math.floorDiv(o.getTimestamp(), barMs), o);
        }
        Map<Long, Double> topPosByBucket = new HashMap<>();
        for (LongShortRatioData r : store.getLongShortRatio(symbol, "TOP_POSITION", period, lim)) {
            topPosByBucket.put(Math.floorDiv(r.getTimestamp(), barMs), r.getRatioValue());
        }

        List<Map<String, Object>> points = new ArrayList<>(klines.size());
        for (KlineData k : klines) {
            double vol = k.getQuoteVolume();
            Double buyPct = vol <= 0 ? null
                    : Math.round(parseDouble(k.getTakerBuyQuoteAssetVolume()) / vol * 1000.0) / 10.0;
            long bucket = Math.floorDiv(k.getOpenTime(), barMs);
            OpenInterestData oi = oiByBucket.get(bucket);

            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", k.getOpenTime());
            p.put("close", k.getClosePrice());
            p.put("buyPct", buyPct);
            p.put("oiCoin", oi == null ? null : oi.getOpenInterestValue());
            p.put("oiUsd", oi == null ? null : oi.getOpenInterestNotionalValue());
            p.put("topPos", topPosByBucket.get(bucket));
            points.add(p);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", symbol);
        body.put("period", period);
        body.put("points", points);
        return ResponseEntity.ok(body);
    }

    /** 周期字符串 → 毫秒; 未知周期按 1h 处理(与 JdbcDataStore 的查询口径一致即可)。 */
    private static long barMillis(String period) {
        return switch (period == null ? "1h" : period) {
            case "5m" -> 300_000L;
            case "15m" -> 900_000L;
            case "30m" -> 1_800_000L;
            case "4h" -> 14_400_000L;
            case "1d" -> 86_400_000L;
            default -> 3_600_000L;
        };
    }

    private static double parseDouble(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
