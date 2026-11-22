package com.ba.analyzer.service;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.model.OpenInterestData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 启动扫描（四个捕捉器，独立页面，不走分析器框架）。
 *
 * <p>背景: {@link HourlyRiseOiService} 要求「N 根 1h 连阳 + OI 净增」，只能抓到行情中段，
 * 且 OI 是滞后一小时的整点快照。EVAA 那类行情（OI 只 +57%、价格 +510%）用 OI 条件筛，
 * 结构上就会漏掉。这里改用「量能 + 形态 + 持仓结构」四条线：
 *
 * <ul>
 *   <li><b>volume-spike</b> 放量突破: 点火那一根（涨幅 + 量能倍数 + 站上 20 日高）。</li>
 *   <li><b>flag-breakout</b> 高位旗形: 大涨后窄幅横盘，放量突破旗形上沿。</li>
 *   <li><b>pullback-confirm</b> 突破回踩: 脉冲后回踩 38.2%~78.6% 且不破启动前收盘，放量收阳。</li>
 *   <li><b>retail-short</b> 散户空头拥挤: 点火前的观察名单（账户比 ≤0.55 + 大户比 ≥1.3 抬升）。</li>
 * </ul>
 *
 * <p>四个捕捉器共用一次全市场加载（1h + 1d K 线、1h OI、多空比、费率），
 * 结果按 type 分槽缓存在内存，不落库。
 */
@Slf4j
@Service
public class SetupScanService {

    public static final String TYPE_VOLUME_SPIKE = "volume-spike";
    public static final String TYPE_FLAG_BREAKOUT = "flag-breakout";
    public static final String TYPE_PULLBACK_CONFIRM = "pullback-confirm";
    public static final String TYPE_RETAIL_SHORT = "retail-short";

    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;

    /** 1h K 线: 脉冲最多回溯 48 根 + 前置 24 根均量。 */
    private static final int H1_LIMIT = 120;
    /** 1d K 线: 30 日涨幅 + 20 日高。 */
    private static final int D1_LIMIT = 40;
    /** 1h OI: 算 24h 变化。 */
    private static final int OI_LIMIT = 30;
    /** 多空比: 最新值 + 抬升对比。 */
    private static final int LSR_LIMIT = 10;

    /** 最新已收盘 1h 超过 3 小时视为同步滞后。 */
    private static final long KLINE_STALE_MS = 3 * HOUR_MS;
    /** 多空比超过 6 小时视为滞后。 */
    private static final long RATIO_STALE_MS = 6 * HOUR_MS;
    /** OI 整点快照容差。 */
    private static final long OI_TOLERANCE_MS = 90 * 60_000L;

    /** 缓存最长复用 1 小时（与调度周期一致）。 */
    private static final long CACHE_MAX_AGE_MS = 3_600_000L;
    /** 拿不到扫描锁最多等这么久。 */
    private static final long DEFAULT_LOCK_WAIT_MS = 30_000L;

    /** 筛选器定义：type → (标题, 说明)。 */
    public record Def(String type, String title, String desc) {
    }

    public static final List<Def> DEFS = List.of(
            new Def(TYPE_VOLUME_SPIKE, "放量突破",
                    "单根已收盘 1h 涨幅 ≥5% 且成交额 ≥ 前 24 根均量 10 倍，收盘站上 20 日高"),
            new Def(TYPE_FLAG_BREAKOUT, "高位旗形",
                    "30 日涨幅 ≥100% 后横盘 10 根 1h（振幅 ≤8%），放量突破旗形上沿"),
            new Def(TYPE_PULLBACK_CONFIRM, "突破回踩",
                    "48 根内出现过 ≥30% + 20 倍量的脉冲，回踩 38.2%~78.6% 且不破启动前收盘，放量收阳"),
            new Def(TYPE_RETAIL_SHORT, "散户空头拥挤",
                    "散户账户多空比 ≤0.55（≤35% 做多）+ 大户持仓比 ≥1.3 且抬升，等点火")
    );

    /**
     * 命中项。百分比字段是小数（0.6939 = 69.39%），与 AlertService / HourlyRiseOiService 一致；
     * volumeRatio / riskReward 是倍数（189.0 = 189 倍）。
     */
    public record SetupMatch(
            String symbol,
            double price,
            /** 触发根涨幅（1h close/open）。 */
            double changePercent,
            /** 前置涨幅（旗形用 30 日，其余为 0）。 */
            double priorGainPercent,
            /** 触发根成交额 / 基准均量。 */
            double volumeRatio,
            /** volume-spike 为 0；flag 为旗形振幅；pullback 为回踩比例。 */
            double rangePercent,
            /** 关键位：20 日高 / 旗形上沿 / 脉冲高点 / 0。 */
            double breakoutPrice,
            double stopPrice,
            double targetPrice,
            double riskReward,
            /** OI 24h 变化（仅 retail-short 计算，其余为 0）。 */
            double oiChange24h,
            /** 散户账户多空比（仅 retail-short）。 */
            double retailRatio,
            /** 大户持仓多空比（仅 retail-short）。 */
            double topPositionRatio,
            /** 资金费率（小数，0.0001 = 0.01%）。 */
            double fundingRate,
            double quoteVolume24h,
            long triggerTime,
            int windowBars,
            String note) {
    }

    /** 一次扫描的完整结果。 */
    public record ScanResult(long scannedAt, String type, int totalSymbols, int matchedCount,
                             List<SetupMatch> matches) {
    }

    /** 扫描正在进行且没有可用缓存时抛出，由 Controller 映射为 503。 */
    public static class ScanBusyException extends RuntimeException {
        public ScanBusyException() {
            super("setup scan is already running");
        }
    }

    private final DataFetchService dataFetchService;
    private final SymbolService symbolService;
    private final AppProperties props;
    private final long lockWaitMs;
    private final java.util.function.LongSupplier clock;

    private final Map<String, ScanResult> slots = new ConcurrentHashMap<>();
    private final ReentrantLock scanLock = new ReentrantLock();
    /** 已完成的整轮扫描次数，用于合并并发刷新。 */
    private volatile long scanCount;

    @Autowired
    public SetupScanService(DataFetchService dataFetchService, SymbolService symbolService,
                            AppProperties props) {
        this(dataFetchService, symbolService, props, DEFAULT_LOCK_WAIT_MS, System::currentTimeMillis);
    }

    /** 供测试注入锁等待时长与时钟（生产用上面的三参构造器）。 */
    SetupScanService(DataFetchService dataFetchService, SymbolService symbolService,
                     AppProperties props, long lockWaitMs, java.util.function.LongSupplier clock) {
        this.dataFetchService = dataFetchService;
        this.symbolService = symbolService;
        this.props = props;
        this.lockWaitMs = lockWaitMs;
        this.clock = clock;
    }

    /** 空值/未知 type 归一化；未知 type 抛 IllegalArgumentException（Controller 映射 400）。 */
    public static String normalizeType(String type) {
        if (type == null || type.isBlank()) return TYPE_VOLUME_SPIKE;
        String t = type.trim().toLowerCase(Locale.ROOT);
        for (Def d : DEFS) {
            if (d.type().equals(t)) return t;
        }
        throw new IllegalArgumentException("unknown setup scan type: " + type);
    }

    /**
     * 取结果：{@code refresh=false} 且有缓存时直接返回（页面轮询路径，不取锁）；
     * 否则取扫描锁，等锁期间如果有人刚扫完（完成计数变了）就直接复用。
     */
    public ScanResult getLatest(String type, boolean refresh) {
        String t = normalizeType(type);
        long now = clock.getAsLong();
        ScanResult cached = slots.get(t);
        if (!refresh && cached != null && now - cached.scannedAt() < CACHE_MAX_AGE_MS) return cached;

        long seen = scanCount;
        boolean locked;
        try {
            locked = scanLock.tryLock(lockWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScanBusyException();
        }
        if (!locked) {
            if (cached != null) return cached;
            throw new ScanBusyException();
        }
        try {
            if (scanCount > seen) {
                ScanResult again = slots.get(t);
                if (again != null) return again;
            }
            return doScanAll().getOrDefault(t,
                    new ScanResult(clock.getAsLong(), t, 0, 0, List.of()));
        } finally {
            scanLock.unlock();
        }
    }

    /** 立即整轮扫描并写入四个缓存槽（调度器走这里，不做缓存判断）。 */
    public Map<String, ScanResult> scanAll() {
        scanLock.lock();
        try {
            return doScanAll();
        } finally {
            scanLock.unlock();
        }
    }

    // ======================== 扫描主体 ========================

    private Map<String, ScanResult> doScanAll() {
        long startedAt = clock.getAsLong();
        List<String> symbols = symbolService.getSymbolNames();
        if (symbols == null || symbols.isEmpty()) {
            Map<String, ScanResult> empty = new LinkedHashMap<>();
            for (Def d : DEFS) {
                empty.put(d.type(), new ScanResult(startedAt, d.type(), 0, 0, List.of()));
            }
            slots.putAll(empty);
            scanCount++;
            log.warn("setup scan: no symbols (sync service may not have run yet)");
            return empty;
        }

        AppProperties.SetupScanConfig cfg = props.getSetupScan();

        Map<String, List<KlineData>> h1Raw = nullToEmpty(dataFetchService.fetchKlines(symbols, "1h", H1_LIMIT));
        Map<String, List<KlineData>> d1Raw = nullToEmpty(dataFetchService.fetchKlines(symbols, "1d", D1_LIMIT));
        Map<String, List<OpenInterestData>> oiRaw =
                nullToEmpty(dataFetchService.fetchOpenInterestHistoryBatch(symbols, "1h", OI_LIMIT));
        Map<String, List<LongShortRatioData>> retailRaw = nullToEmpty(
                dataFetchService.getLongShortRatioBatch(symbols, "GLOBAL_ACCOUNT", "1h", LSR_LIMIT));
        Map<String, List<LongShortRatioData>> topRaw = nullToEmpty(
                dataFetchService.getLongShortRatioBatch(symbols, "TOP_POSITION", "1h", LSR_LIMIT));
        Map<String, Double> funding = dataFetchService.getLastFundingRates();
        if (funding == null) funding = Map.of();

        List<SetupMatch> spikes = new ArrayList<>();
        List<SetupMatch> flags = new ArrayList<>();
        List<SetupMatch> pullbacks = new ArrayList<>();
        List<SetupMatch> retails = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : h1Raw.entrySet()) {
            String symbol = entry.getKey();
            List<KlineData> h1 = closedOnly(entry.getValue(), startedAt);
            if (h1.isEmpty()) continue;
            if (startedAt - h1.get(h1.size() - 1).getCloseTime() > KLINE_STALE_MS) continue;

            double qv24 = quoteVolume24h(h1);
            if (qv24 < cfg.getLiquidity().getMinQuoteVolume24h()) continue;

            Ctx ctx = new Ctx(symbol, h1, closedOnly(d1Raw.get(symbol), startedAt),
                    orEmpty(oiRaw.get(symbol)), orEmpty(retailRaw.get(symbol)),
                    orEmpty(topRaw.get(symbol)), funding.getOrDefault(symbol, 0.0),
                    qv24, startedAt);
            try {
                addIfPresent(spikes, detectVolumeSpike(ctx, cfg.getVolumeSpike()));
                addIfPresent(flags, detectFlagBreakout(ctx, cfg.getFlagBreakout()));
                addIfPresent(pullbacks, detectPullbackConfirm(ctx, cfg.getPullbackConfirm()));
                addIfPresent(retails, detectRetailShort(ctx, cfg.getRetailShort()));
            } catch (RuntimeException e) {
                log.debug("setup scan: skip {} ({})", symbol, e.toString());
            }
        }

        spikes.sort(Comparator.comparingDouble(SetupMatch::volumeRatio).reversed()
                .thenComparing(Comparator.comparingDouble(SetupMatch::changePercent).reversed()));
        flags.sort(Comparator.comparingDouble(SetupMatch::riskReward).reversed()
                .thenComparing(Comparator.comparingDouble(SetupMatch::priorGainPercent).reversed()));
        pullbacks.sort(Comparator.comparingDouble(SetupMatch::riskReward).reversed());
        retails.sort(Comparator.comparingDouble(SetupMatch::retailRatio));

        Map<String, ScanResult> results = new LinkedHashMap<>();
        results.put(TYPE_VOLUME_SPIKE, result(startedAt, TYPE_VOLUME_SPIKE, symbols.size(), spikes));
        results.put(TYPE_FLAG_BREAKOUT, result(startedAt, TYPE_FLAG_BREAKOUT, symbols.size(), flags));
        results.put(TYPE_PULLBACK_CONFIRM,
                result(startedAt, TYPE_PULLBACK_CONFIRM, symbols.size(), pullbacks));
        results.put(TYPE_RETAIL_SHORT, result(startedAt, TYPE_RETAIL_SHORT, symbols.size(), retails));
        slots.putAll(results);
        scanCount++;

        log.info("setup scan: symbols={} spike={} flag={} pullback={} retailShort={} elapsed={}ms",
                symbols.size(), spikes.size(), flags.size(), pullbacks.size(), retails.size(),
                clock.getAsLong() - startedAt);
        return results;
    }

    private static ScanResult result(long at, String type, int total, List<SetupMatch> matches) {
        return new ScanResult(at, type, total, matches.size(), List.copyOf(matches));
    }

    private static void addIfPresent(List<SetupMatch> out, SetupMatch m) {
        if (m != null) out.add(m);
    }

    // ======================== 捕捉器 1: 放量突破 ========================

    private SetupMatch detectVolumeSpike(Ctx c, AppProperties.VolumeSpikeConfig cfg) {
        List<KlineData> h = c.h1();
        int last = h.size() - 1;
        if (last < cfg.getAvgBars()) return null;

        KlineData trig = h.get(last);
        if (trig.getOpenPrice() <= 0) return null;

        double change = trig.getClosePrice() / trig.getOpenPrice() - 1;
        if (change < cfg.getMinChangePercent() / 100) return null;

        double avg = avgQuoteVolume(h, last - cfg.getAvgBars(), last);
        if (avg <= 0) return null;
        double mult = trig.getQuoteVolume() / avg;
        if (mult < cfg.getMinVolumeMultiple()) return null;

        List<KlineData> d = c.d1();
        if (d.size() < cfg.getBreakoutDays()) return null;
        int from = d.size() - cfg.getBreakoutDays();
        double hi = Double.NEGATIVE_INFINITY;
        double lo = Double.POSITIVE_INFINITY;
        for (int i = from; i < d.size(); i++) {
            hi = Math.max(hi, d.get(i).getHighPrice());
            lo = Math.min(lo, d.get(i).getLowPrice());
        }
        double close = trig.getClosePrice();
        if (cfg.isRequireBreakout() && !(close > hi)) return null;
        if (hi <= 0 || lo <= 0 || lo >= hi) return null;

        double stop = trig.getLowPrice();
        double target = hi + (hi - lo);
        double rr = riskReward(close, stop, target);

        String note = String.format(Locale.ROOT,
                "1h %+.1f%% · 量 %.0fx · %s %.4f",
                change * 100, mult,
                close > hi ? "破 " + cfg.getBreakoutDays() + " 日高" : "20 日高",
                hi);
        return new SetupMatch(c.symbol(), close, change, 0, mult, 0, hi, stop, target, rr,
                0, 0, 0, c.funding(), c.quoteVolume24h(), trig.getCloseTime(), 1, note);
    }

    // ======================== 捕捉器 2: 高位旗形 ========================

    private SetupMatch detectFlagBreakout(Ctx c, AppProperties.FlagBreakoutConfig cfg) {
        List<KlineData> h = c.h1();
        int last = h.size() - 1;
        int flagStart = last - cfg.getFlagBars();
        if (flagStart <= 0) return null;

        KlineData trig = h.get(last);
        double flagHigh = Double.NEGATIVE_INFINITY;
        double flagLow = Double.POSITIVE_INFINITY;
        for (int i = flagStart; i < last; i++) {
            flagHigh = Math.max(flagHigh, h.get(i).getHighPrice());
            flagLow = Math.min(flagLow, h.get(i).getLowPrice());
        }
        if (flagLow <= 0 || flagHigh <= flagLow) return null;

        double range = flagHigh / flagLow - 1;
        if (range > cfg.getMaxFlagRangePercent() / 100) return null;
        if (trig.getClosePrice() <= flagHigh) return null;

        double avg = avgQuoteVolume(h, flagStart, last);
        if (avg <= 0) return null;
        double mult = trig.getQuoteVolume() / avg;
        if (mult < cfg.getMinBreakoutVolumeMultiple()) return null;

        List<KlineData> d = c.d1();
        if (d.size() < cfg.getPriorGainDays() + 1) return null;
        double base = d.get(d.size() - 1 - cfg.getPriorGainDays()).getClosePrice();
        if (base <= 0) return null;
        double priorGain = trig.getClosePrice() / base - 1;
        if (priorGain < cfg.getMinPriorGainPercent() / 100) return null;

        double stop = flagLow;
        double target = flagHigh + (flagHigh - flagLow) * cfg.getTargetFlagHeights();
        double rr = riskReward(trig.getClosePrice(), stop, target);

        String note = String.format(Locale.ROOT,
                "30 日 %+.0f%% · 旗形 %dh 振幅 %.1f%% · 破 %.4f · 量 %.1fx",
                priorGain * 100, cfg.getFlagBars(), range * 100, flagHigh, mult);
        return new SetupMatch(c.symbol(), trig.getClosePrice(), trig.getClosePrice() / trig.getOpenPrice() - 1,
                priorGain, mult, range, flagHigh, stop, target, rr,
                0, 0, 0, c.funding(), c.quoteVolume24h(), trig.getCloseTime(), cfg.getFlagBars(), note);
    }

    // ======================== 捕捉器 3: 突破回踩 ========================

    private SetupMatch detectPullbackConfirm(Ctx c, AppProperties.PullbackConfirmConfig cfg) {
        List<KlineData> h = c.h1();
        int last = h.size() - 1;
        if (last < cfg.getSpikeLookbackBars() + 1) return null;

        // 1) 在最近 lookback 根里找最近一次脉冲（不含触发根本身）
        int from = Math.max(25, last - cfg.getSpikeLookbackBars());
        int spikeIdx = -1;
        for (int i = last - 1; i >= from; i--) {
            KlineData b = h.get(i);
            if (b.getOpenPrice() <= 0) continue;
            if ((b.getClosePrice() / b.getOpenPrice() - 1) * 100 < cfg.getMinSpikeChangePercent()) continue;
            double avg = avgQuoteVolume(h, Math.max(0, i - 24), i);
            if (avg <= 0) continue;
            if (b.getQuoteVolume() / avg < cfg.getMinSpikeVolumeMultiple()) continue;
            spikeIdx = i;
            break;
        }
        if (spikeIdx < 0) return null;

        KlineData spike = h.get(spikeIdx);
        double spikeLow = spike.getLowPrice();
        double spikeHigh = spike.getHighPrice();
        double preSpikeClose = h.get(spikeIdx - 1).getClosePrice();
        if (spikeLow <= 0 || spikeHigh <= spikeLow || preSpikeClose <= 0) return null;

        // 2) 回踩深度 38.2%~78.6%，且不破启动前收盘
        double pullbackLow = Double.POSITIVE_INFINITY;
        for (int i = spikeIdx + 1; i <= last; i++) {
            pullbackLow = Math.min(pullbackLow, h.get(i).getLowPrice());
        }
        if (!Double.isFinite(pullbackLow)) return null;
        double retrace = (spikeHigh - pullbackLow) / (spikeHigh - spikeLow);
        if (retrace < cfg.getMinRetrace() || retrace > cfg.getMaxRetrace()) return null;
        if (pullbackLow < preSpikeClose * 0.99) return null;

        // 3) 回踩段缩量（触发根之前）
        double pullbackAvg = avgQuoteVolume(h, spikeIdx + 1, last);
        if (pullbackAvg > 0 && pullbackAvg > spike.getQuoteVolume() * cfg.getMaxPullbackVolumeRatio()) {
            return null;
        }

        // 4) 确认根：收阳 + 站上上一根高点 + 量能不低于回踩段均量
        KlineData trig = h.get(last);
        if (trig.getClosePrice() <= trig.getOpenPrice()) return null;
        if (trig.getClosePrice() <= h.get(last - 1).getHighPrice()) return null;
        // 用「回踩段」而不是「最近 6 根」做基准：脉冲根的量会把基准拉高，导致真正确认时反而量比不足
        double prevAvg = avgQuoteVolume(h, Math.max(spikeIdx + 1, last - 6), last);
        if (prevAvg > 0 && trig.getQuoteVolume() < prevAvg * cfg.getMinConfirmVolumeRatio()) return null;

        double stop = pullbackLow;
        double target = spikeHigh;
        double rr = riskReward(trig.getClosePrice(), stop, target);

        String note = String.format(Locale.ROOT,
                "脉冲 %+.0f%% · 回踩 %.1f%% · 确认 %+.1f%% · 缩量 %.0f%%",
                (spike.getClosePrice() / spike.getOpenPrice() - 1) * 100,
                retrace * 100,
                (trig.getClosePrice() / trig.getOpenPrice() - 1) * 100,
                spike.getQuoteVolume() > 0 ? pullbackAvg / spike.getQuoteVolume() * 100 : 0);
        return new SetupMatch(c.symbol(), trig.getClosePrice(),
                trig.getClosePrice() / trig.getOpenPrice() - 1,
                0, trig.getQuoteVolume() / Math.max(prevAvg, 1e-9), retrace,
                spikeHigh, stop, target, rr,
                0, 0, 0, c.funding(), c.quoteVolume24h(), trig.getCloseTime(), last - spikeIdx, note);
    }

    // ======================== 捕捉器 4: 散户空头拥挤 ========================

    private SetupMatch detectRetailShort(Ctx c, AppProperties.RetailShortConfig cfg) {
        if (c.retail().isEmpty() || c.top().isEmpty()) return null;

        LongShortRatioData retail = c.retail().get(c.retail().size() - 1);
        LongShortRatioData top = c.top().get(c.top().size() - 1);
        if (c.now() - retail.getTimestamp() > RATIO_STALE_MS) return null;
        if (c.now() - top.getTimestamp() > RATIO_STALE_MS) return null;

        double retailRatio = retail.getRatioValue();
        double topRatio = top.getRatioValue();
        if (retailRatio <= 0 || topRatio <= 0) return null;
        if (retailRatio > cfg.getMaxRetailRatio()) return null;
        if (topRatio < cfg.getMinTopPositionRatio()) return null;

        int idx = Math.max(0, c.top().size() - 1 - cfg.getTopRiseLookback());
        double topPrev = c.top().get(idx).getRatioValue();
        if (!(topRatio > topPrev)) return null;

        double oiChg = oiChange24h(c.oi(), OI_TOLERANCE_MS);
        if (oiChg * 100 < cfg.getMinOiChange24hPercent()) return null;

        KlineData lastBar = c.h1().get(c.h1().size() - 1);
        double longAccountPct = retailRatio / (1 + retailRatio) * 100;

        String note = String.format(Locale.ROOT,
                "散户 %.2f（%.0f%% 做多）· 大户 %.2f（%+.2f）· OI 24h %+.1f%% · 费率 %+.4f%%",
                retailRatio, longAccountPct, topRatio, topRatio - topPrev,
                oiChg * 100, c.funding() * 100);
        return new SetupMatch(c.symbol(), lastBar.getClosePrice(), 0, 0, 0, 0, 0, 0, 0, 0,
                oiChg, retailRatio, topRatio, c.funding(), c.quoteVolume24h(),
                retail.getTimestamp(), 0, note);
    }

    // ======================== 工具 ========================

    private record Ctx(String symbol, List<KlineData> h1, List<KlineData> d1,
                       List<OpenInterestData> oi, List<LongShortRatioData> retail,
                       List<LongShortRatioData> top, double funding,
                       double quoteVolume24h, long now) {
    }

    /** 目标/止损/现价三者构成的盈亏比；任一无效返回 0。 */
    static double riskReward(double price, double stop, double target) {
        if (price <= 0 || stop <= 0 || target <= 0) return 0;
        double risk = price - stop;
        double reward = target - price;
        if (risk <= 0 || reward <= 0) return 0;
        return reward / risk;
    }

    static List<KlineData> closedOnly(List<KlineData> all, long now) {
        if (all == null || all.isEmpty()) return List.of();
        List<KlineData> out = new ArrayList<>(all.size());
        for (KlineData k : all) {
            if (k.getCloseTime() < now) out.add(k);
        }
        return out;
    }

    /** 最近 24 根已收盘 1h 的成交额合计。 */
    static double quoteVolume24h(List<KlineData> h1) {
        int from = Math.max(0, h1.size() - 24);
        double sum = 0;
        for (int i = from; i < h1.size(); i++) sum += h1.get(i).getQuoteVolume();
        return sum;
    }

    static double avgQuoteVolume(List<KlineData> bars, int fromInclusive, int toExclusive) {
        int from = Math.max(0, fromInclusive);
        int to = Math.min(bars.size(), toExclusive);
        if (to <= from) return 0;
        double sum = 0;
        for (int i = from; i < to; i++) sum += bars.get(i).getQuoteVolume();
        return sum / (to - from);
    }

    /** OI 24h 变化（币本位），端点缺失返回 0。 */
    static double oiChange24h(List<OpenInterestData> oi, long tolerance) {
        if (oi == null || oi.size() < 2) return 0;
        OpenInterestData end = oi.get(oi.size() - 1);
        long target = end.getTimestamp() - DAY_MS;
        OpenInterestData start = null;
        long best = Long.MAX_VALUE;
        for (OpenInterestData o : oi) {
            long diff = Math.abs(o.getTimestamp() - target);
            if (diff <= tolerance && diff < best) {
                best = diff;
                start = o;
            }
        }
        if (start == null) return 0;
        double s = parseOi(start);
        double e = parseOi(end);
        return s > 0 ? e / s - 1 : 0;
    }

    private static double parseOi(OpenInterestData oi) {
        String v = oi.getSumOpenInterest();
        if (v == null || v.isBlank()) return 0;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static <K, V> Map<K, V> nullToEmpty(Map<K, V> map) {
        return map == null ? Map.of() : map;
    }
}
