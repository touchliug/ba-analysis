# 小时连涨 + OI 净增扫描 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 每小时扫描全市场，找出「最近 N 根已收盘 1h K 线全部收阳 + 同窗口 `sum_open_interest` 净增」的币，并在独立页面展示。

**Architecture:** 在 ba-analysis 内新增一条与现有分析器框架完全解耦的链路：`HourlyRiseOiService`（扫描 + 按 periods 分槽的内存缓存）→ `HourlyRiseOiController`（`/api/market/hourly-rise-oi`）→ 前端独立页面；`HourlyRiseOiScheduler` 每小时 :10 预热缓存。只读 MySQL，不写库，不碰现有分析器与报告页。

**Tech Stack:** Java 21、Spring Boot 3.2.5、Spring JDBC（经 `DataFetchService`）、JUnit 5 + Mockito、原生 JS（`static/js/app.js`）。

**Spec:** `docs/superpowers/specs/2026-11-10-hourly-rise-oi-design.md`

## Global Constraints

- 只读：本服务不写 MySQL、不访问币安 API。
- 周期固定 `1h`，`periods` 默认 4，clamp 到 2–24。
- OI 只用 `open_interest.sum_open_interest`（币本位数量），**不得**用 `sum_open_interest_value`，也不得用 `OpenInterestData.getOpenInterestValue()`（它会优先读另一列）。
- 只使用**已收盘** K 线（`closeTime < now`）。
- 百分比字段一律用**小数**（`0.0412` = 4.12%），与 `AlertService` 的 `chg1h` 一致；前端负责 ×100。
- 不改现有分析器、`ReportWriter`、`AnalysisScheduler`、分析报告页。
- 配置加在 `binance.hourly-scan.*` 与 `binance.schedule.hourly-scan`；cron 通过占位符读取，不硬编码在注解里。
- 注释与日志用中文，风格与现有代码一致（`log.info("... {} ...", x)`）。
- 每个任务结束时 `mvn -q test` 必须全绿；前端改动用 `node --check src/main/resources/static/js/app.js` 校验语法。

## Review Focus

- **未收盘 K 线混进窗口**：整点后 10 分钟扫描时，当前小时那根是半根；若把它算进 N 根，会得出一个还会变的结论。→ Task 1 的 `ignoresUnclosedBar`。
- **用错 OI 列**：`sum_open_interest_value`（USDT 名义值）会随价格波动，即使币数没增加也可能「增长」。→ Task 1 的 `usesCoinDenominatedOi`。
- **窗口端点对不上**：OI 是整点快照，`windowEnd` 是 `closeTime`（`:59:59.999`），必须用容差匹配；最新一小时 OI 还没同步时要能退化并如实报告区间。→ Task 1 的 `countsSkippedNoOiWhenStartMissing` 与 `fallsBackToLatestOiWhenEndMissing`。
- **缓存被别的 periods 顶掉**：页面选 5 小时后再轮询，不能被调度器的 4 小时结果覆盖。→ Task 2 的 `cacheIsPartitionedByPeriods`。
- **并发刷新打爆 DB**：缓存过期瞬间多个请求同时扫全市场。→ Task 2 的 `concurrentRefreshScansOnce`。
- **空结果被当成故障**：没有币命中时页面应显示空态而不是报错。→ Task 3 的 `returnsEmptyResultNotError`。

---

### Task 1: `HourlyRiseOiService` 扫描逻辑

**Files:**
- Create: `src/main/java/com/ba/analyzer/service/HourlyRiseOiService.java`
- Test: `src/test/java/com/ba/analyzer/service/HourlyRiseOiServiceTest.java`

**Interfaces:**
- Produces: `record Match(String symbol, double price, double priceChangePercent, double oiChangePercent, int periods, long windowStart, long windowEnd, long oiStartTime, long oiEndTime, double quoteVolume24h)`
- Produces: `record ScanResult(long scannedAt, int totalSymbols, int matchedCount, int periods, int skippedNoOi, List<Match> matches)`
- Produces: `ScanResult scan(int periods)` —— 现扫现算并写入缓存槽，不取锁
- Produces: `static int clampPeriods(int periods)`（2–24）
- Produces: `public static final int MIN_PERIODS = 2` / `MAX_PERIODS = 24`
- Consumes: `DataFetchService.fetchKlines(List<String>, String, int)`、`DataFetchService.fetchOpenInterestHistoryBatch(List<String>, String, int)`、`SymbolService.getSymbolNames()`

- [ ] **Step 1: 写失败测试**

新建测试类，用 Mockito mock 两个依赖。`kline(...)` / `oi(...)` 是本类的私有工厂（`KlineData` 的 `@AllArgsConstructor` 含 6 个 transient 缓存字段，不要用它）。

```java
package com.ba.analyzer.service;

import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HourlyRiseOiServiceTest {

    private static final long H = 3_600_000L;

    private DataFetchService dataFetch;
    private SymbolService symbols;
    private HourlyRiseOiService service;

    @BeforeEach
    void setUp() {
        dataFetch = mock(DataFetchService.class);
        symbols = mock(SymbolService.class);
        service = new HourlyRiseOiService(dataFetch, symbols);
        when(symbols.getSymbolNames()).thenReturn(List.of("BTCUSDT"));
    }

    /** 收盘时刻 = 开盘 + 1h - 1ms。 */
    private static KlineData kline(long openTime, double open, double close, double quoteVol) {
        KlineData k = new KlineData();
        k.setOpenTime(openTime);
        k.setCloseTime(openTime + H - 1);
        k.setOpen(String.valueOf(open));
        k.setClose(String.valueOf(close));
        k.setQuoteAssetVolume(String.valueOf(quoteVol));
        return k;
    }

    private static OpenInterestData oi(long ts, double sumCoin, double sumValue) {
        OpenInterestData o = new OpenInterestData();
        o.setTimestamp(ts);
        o.setSumOpenInterest(String.valueOf(sumCoin));
        o.setSumOpenInterestValue(String.valueOf(sumValue));
        return o;
    }

    /** 最近 n 根已收盘 1h 线全部收阳，窗口结束时间 = 最新一根的 closeTime。 */
    private static List<KlineData> risingBars(int n, long lastOpenTime) {
        List<KlineData> list = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            long t = lastOpenTime - i * H;
            list.add(kline(t, 100 + (n - 1 - i), 101 + (n - 1 - i), 1_000_000));
        }
        return list;
    }

    private void stubData(List<KlineData> klines, List<OpenInterestData> ois) {
        when(dataFetch.fetchKlines(anyList(), eq("1h"), anyInt())).thenReturn(Map.of("BTCUSDT", klines));
        when(dataFetch.fetchOpenInterestHistoryBatch(anyList(), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", ois));
    }

    @Test
    void matchesRisingBarsWithOiIncrease() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;   // 上一整点
        long start = lastOpen - 3 * H;
        stubData(risingBars(4, lastOpen), List.of(
                oi(start, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

        HourlyRiseOiService.ScanResult r = service.scan(4);

        assertThat(r.matchedCount()).isEqualTo(1);
        assertThat(r.totalSymbols()).isEqualTo(1);
        assertThat(r.skippedNoOi()).isZero();
        HourlyRiseOiService.Match m = r.matches().get(0);
        assertThat(m.symbol()).isEqualTo("BTCUSDT");
        assertThat(m.price()).isEqualTo(104.0);            // 最后一根的 close
        assertThat(m.priceChangePercent()).isCloseTo(104.0 / 100.0 - 1, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.oiChangePercent()).isCloseTo(1_100.0 / 1_000.0 - 1, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.windowStart()).isEqualTo(start);
        assertThat(m.oiStartTime()).isEqualTo(start);
        assertThat(m.oiEndTime()).isEqualTo(lastOpen + H);
    }

    @Test
    void usesCoinDenominatedOi() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        long start = lastOpen - 3 * H;
        // 币数净增，但名义值净减（价格下跌）→ 仍应命中
        stubData(risingBars(4, lastOpen), List.of(
                oi(start, 1_000, 10_000_000), oi(lastOpen + H, 1_050, 9_000_000)));

        assertThat(service.scan(4).matchedCount()).isEqualTo(1);
    }

    @Test
    void rejectsWhenOiDecreases() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        long start = lastOpen - 3 * H;
        stubData(risingBars(4, lastOpen), List.of(
                oi(start, 1_100, 6_000), oi(lastOpen + H, 1_000, 5_000)));

        HourlyRiseOiService.ScanResult r = service.scan(4);
        assertThat(r.matchedCount()).isZero();
        assertThat(r.skippedNoOi()).isZero();     // 端点齐全，只是没净增
    }

    @Test
    void rejectsRedBarInWindow() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        long start = lastOpen - 3 * H;
        List<KlineData> bars = risingBars(4, lastOpen);
        bars.set(1, kline(start + H, 102, 101, 1_000_000));   // 中间一根阴线
        stubData(bars, List.of(oi(start, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

        assertThat(service.scan(4).matchedCount()).isZero();
    }

    @Test
    void ignoresUnclosedBar() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        long start = lastOpen - 3 * H;
        List<KlineData> bars = new ArrayList<>(risingBars(4, lastOpen));
        // 未收盘的当前小时：阴线；若被算进窗口就不该命中
        long curOpen = lastOpen + H;
        KlineData openBar = kline(curOpen, 200, 150, 1_000_000);
        openBar.setCloseTime(curOpen + H - 1);     // 明显 > now
        bars.add(openBar);
        stubData(bars, List.of(oi(start, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

        HourlyRiseOiService.ScanResult r = service.scan(4);
        assertThat(r.matchedCount()).isEqualTo(1);
        assertThat(r.matches().get(0).windowEnd()).isEqualTo(lastOpen + H - 1);
    }

    @Test
    void fallsBackToLatestOiWhenEndMissing() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        long start = lastOpen - 3 * H;
        // 只有起点和窗口最后一小时的 OI（窗口结束点还没同步）
        stubData(risingBars(4, lastOpen), List.of(
                oi(start, 1_000, 5_000), oi(lastOpen, 1_050, 5_500)));

        HourlyRiseOiService.Match m = service.scan(4).matches().get(0);
        assertThat(m.oiEndTime()).isEqualTo(lastOpen);       // 退化为窗口内最新点
        assertThat(m.oiChangePercent()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void countsSkippedNoOiWhenStartMissing() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        // 只有窗口结束附近的 OI，起点缺失
        stubData(risingBars(4, lastOpen), List.of(oi(lastOpen + H, 1_100, 6_000)));

        HourlyRiseOiService.ScanResult r = service.scan(4);
        assertThat(r.matchedCount()).isZero();
        assertThat(r.skippedNoOi()).isEqualTo(1);
    }

    @Test
    void skipsWhenKlinesInsufficient() {
        long lastOpen = (System.currentTimeMillis() / H) * H - H;
        stubData(risingBars(2, lastOpen), List.of(oi(lastOpen - H, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

        assertThat(service.scan(4).matchedCount()).isZero();
    }

    @Test
    void skipsStaleSymbol() {
        long oldOpen = (System.currentTimeMillis() / H) * H - 10 * H;   // 10 小时前就断了
        stubData(risingBars(4, oldOpen), List.of(oi(oldOpen - 3 * H, 1_000, 5_000), oi(oldOpen + H, 1_100, 6_000)));

        assertThat(service.scan(4).matchedCount()).isZero();
    }

    @Test
    void clampsPeriodsToSupportedRange() {
        when(dataFetch.fetchKlines(anyList(), anyString(), anyInt())).thenReturn(Map.of());
        when(dataFetch.fetchOpenInterestHistoryBatch(anyList(), anyString(), anyInt())).thenReturn(Map.of());

        assertThat(service.scan(1).periods()).isEqualTo(2);
        assertThat(service.scan(99).periods()).isEqualTo(24);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=HourlyRiseOiServiceTest test`
Expected: 编译失败（`HourlyRiseOiService` 不存在）

- [ ] **Step 3: 实现服务**

文件骨架（方法体按下面规则写）：

```java
@Slf4j
@Service
public class HourlyRiseOiService {

    public static final int MIN_PERIODS = 2;
    public static final int MAX_PERIODS = 24;

    private static final long HOUR_MS = 3_600_000L;
    /** OI 端点容差：OI 是整点快照，窗口端点是 :59:59.999。 */
    private static final long OI_TOLERANCE_MS = 30 * 60_000L;
    /** 端口点退化时，距窗口结束最远允许 2 小时。 */
    private static final long OI_FALLBACK_MAX_MS = 2 * HOUR_MS;
    /** 退化端点至少要覆盖 2 小时，否则不算「窗口净增」。 */
    private static final long OI_MIN_SPAN_MS = 2 * HOUR_MS;
    /** 最新已收盘 K 线超过 2 小时视为同步滞后。 */
    private static final long KLINE_STALE_MS = 2 * HOUR_MS;
    /** 多取一些根，用于 24h 成交额与停牌缺口。 */
    private static final int EXTRA_BARS = 20;

    public record Match(...) {}
    public record ScanResult(...) {}

    private final DataFetchService dataFetchService;
    private final SymbolService symbolService;
    private final Map<Integer, ScanResult> slots = new ConcurrentHashMap<>();

    public HourlyRiseOiService(DataFetchService dataFetchService, SymbolService symbolService) { ... }

    public static int clampPeriods(int periods) {
        return Math.min(Math.max(periods, MIN_PERIODS), MAX_PERIODS);
    }

    public ScanResult scan(int periods) { ... }

    /** 只解析取 sum_open_interest(币数)；空白或非法返回 0（调用方据此跳过）。 */
    static double sumOpenInterest(OpenInterestData oi) { ... }

    private static OpenInterestData nearest(List<OpenInterestData> oi, long target, long tolerance) { ... }

    private static OpenInterestData latestBetween(List<OpenInterestData> oi, long minTs, long maxTs) { ... }
}
```

`scan(periods)` 的判定顺序（每一步都要按此顺序，跳过语义不能混）：

1. `int n = clampPeriods(periods); long now = System.currentTimeMillis();`
2. `symbols = symbolService.getSymbolNames()`；空则直接返回空结果（`totalSymbols=0`）。
3. `limit = Math.max(n + EXTRA_BARS, 24)`；两条批量查询：`fetchKlines(symbols,"1h",limit)`、`fetchOpenInterestHistoryBatch(symbols,"1h",limit)`。
4. 遍历 `klineMap` 的每个 entry（没有 K 线的币自然不在其中）：
   - `closed = 只保留 closeTime < now 的根`；空则跳过。
   - `now - closed 最后一根.closeTime > KLINE_STALE_MS` → 跳过（同步滞后）。
   - `closed.size() < n` → 跳过。
   - `win = closed 末尾 n 根`；不是每根 `close > open` → 跳过。
   - 端点：`oiStart = nearest(oiList, win[0].openTime, OI_TOLERANCE_MS)`；`oiEnd = nearest(oiList, win最后.closeTime, OI_TOLERANCE_MS)`；`oiEnd == null` 时 `oiEnd = latestBetween(oiList, win[0].openTime + OI_MIN_SPAN_MS, now)`，且若 `|windowEnd - oiEnd.timestamp| > OI_FALLBACK_MAX_MS` 则置 null。
   - `oiStart == null || oiEnd == null || oiEnd.timestamp <= oiStart.timestamp` → `skippedNoOi++` 并跳过。
   - `s = sumOpenInterest(oiStart); e = sumOpenInterest(oiEnd);` `s <= 0 || e <= s` → 跳过（不计数）。
   - 组装 Match：`price = win最后.closePrice`、`priceChangePercent = price / win[0].openPrice - 1`（`openPrice <= 0` 时记 0）、`oiChangePercent = e / s - 1`、`windowStart = win[0].openTime`、`windowEnd = win最后.closeTime`、`oiStartTime/oiEndTime` 用实际端点时间戳、`quoteVolume24h = closed 末尾 min(24, size) 根的 quoteVolume 之和`。
5. 排序：`oiChangePercent` 降序，再 `priceChangePercent` 降序。
6. `ScanResult(scannedAt=now, totalSymbols=symbols.size(), matchedCount=matches.size(), periods=n, skippedNoOi=skipped, matches=List.copyOf(matches))`，写入 `slots.put(n, result)` 后返回；日志一条：
   `log.info("hourly-rise-oi scan: symbols={} matched={} skippedNoOi={} periods={} elapsed={}ms", ...)`。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=HourlyRiseOiServiceTest test`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/ba/analyzer/service/HourlyRiseOiService.java src/test/java/com/ba/analyzer/service/HourlyRiseOiServiceTest.java
git commit -m "feat(analysis): add hourly rise + OI net-increase scanner"
```

---

### Task 2: 缓存分槽 + 刷新 + 并发单飞

**Files:**
- Modify: `src/main/java/com/ba/analyzer/service/HourlyRiseOiService.java`
- Test: `src/test/java/com/ba/analyzer/service/HourlyRiseOiServiceTest.java`

**Interfaces:**
- Produces: `ScanResult getLatest(int periods, boolean refresh)`
- Produces: `public static class ScanBusyException extends RuntimeException`
- Consumes: `scan(int)`（Task 1）

- [ ] **Step 1: 写失败测试**

追加到 `HourlyRiseOiServiceTest`：

```java
@Test
void returnsCacheWhenNotRefreshing() {
    long lastOpen = (System.currentTimeMillis() / H) * H - H;
    stubData(risingBars(4, lastOpen), List.of(
            oi(lastOpen - 3 * H, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

    service.getLatest(4, false);
    service.getLatest(4, false);

    verify(dataFetch, times(1)).fetchKlines(anyList(), anyString(), anyInt());
}

@Test
void refreshForcesRescan() {
    long lastOpen = (System.currentTimeMillis() / H) * H - H;
    stubData(risingBars(4, lastOpen), List.of(
            oi(lastOpen - 3 * H, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

    service.getLatest(4, true);
    service.getLatest(4, true);

    verify(dataFetch, times(2)).fetchKlines(anyList(), anyString(), anyInt());
}

@Test
void cacheIsPartitionedByPeriods() {
    long lastOpen = (System.currentTimeMillis() / H) * H - H;
    stubData(risingBars(4, lastOpen), List.of(
            oi(lastOpen - 3 * H, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000)));

    service.getLatest(4, true);
    service.getLatest(5, false);          // 5 槽没有缓存 → 扫一次
    service.getLatest(4, false);          // 4 槽的缓存不能被 5 顶掉

    verify(dataFetch, times(2)).fetchKlines(anyList(), anyString(), anyInt());
    assertThat(service.getLatest(4, false).periods()).isEqualTo(4);
    assertThat(service.getLatest(5, false).periods()).isEqualTo(5);
}

@Test
void concurrentRefreshScansOnce() throws Exception {
    long lastOpen = (System.currentTimeMillis() / H) * H - H;
    when(dataFetch.fetchKlines(anyList(), anyString(), anyInt())).thenAnswer(inv -> {
        Thread.sleep(300);                // 让两个线程真的撞在一起
        return Map.of("BTCUSDT", risingBars(4, lastOpen));
    });
    when(dataFetch.fetchOpenInterestHistoryBatch(anyList(), anyString(), anyInt()))
            .thenReturn(Map.of("BTCUSDT", List.of(
                    oi(lastOpen - 3 * H, 1_000, 5_000), oi(lastOpen + H, 1_100, 6_000))));

    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    Runnable task = () -> {
        try { go.await(); } catch (InterruptedException ignored) { }
        service.getLatest(4, true);
    };
    Thread a = new Thread(task);
    Thread b = new Thread(task);
    a.start(); b.start();
    go.countDown();
    a.join(); b.join();

    verify(dataFetch, times(1)).fetchKlines(anyList(), anyString(), anyInt());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=HourlyRiseOiServiceTest test`
Expected: 编译失败（`getLatest` 不存在）

- [ ] **Step 3: 实现**

```java
private static final long LOCK_WAIT_MS = 10_000L;

private final Map<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();

public ScanResult getLatest(int periods, boolean refresh) {
    int n = clampPeriods(periods);
    ScanResult cached = slots.get(n);
    if (!refresh && cached != null) return cached;

    long requestStarted = System.currentTimeMillis();
    ReentrantLock lock = locks.computeIfAbsent(n, k -> new ReentrantLock());
    boolean locked;
    try {
        locked = lock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ScanBusyException();
    }
    if (!locked) {
        if (cached != null) return cached;
        throw new ScanBusyException();
    }
    try {
        ScanResult again = slots.get(n);
        // 只合并「我等待期间别人刚扫出来的」结果；排队时既有的旧缓存不算（refresh=true 必须真的重扫）
        if (again != null && again.scannedAt() >= requestStarted) return again;
        return scan(n);
    } finally {
        lock.unlock();
    }
}
```

注意两点：
- `refresh=false` 且有缓存时必须**完全不取锁**（`returnsCacheWhenNotRefreshing` 依赖这一点，也是页面轮询的常态路径）。
- 去重判据是「缓存生成时间 ≥ 本次请求开始时间」，不能用固定 TTL：否则连续两次 `refresh=true` 会被误合并，破坏「立即扫描」按钮。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=HourlyRiseOiServiceTest test`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/ba/analyzer/service/HourlyRiseOiService.java src/test/java/com/ba/analyzer/service/HourlyRiseOiServiceTest.java
git commit -m "feat(analysis): cache hourly scan per periods with single-flight refresh"
```

---

### Task 3: 配置 + 调度器 + Controller

**Files:**
- Modify: `src/main/java/com/ba/analyzer/config/AppProperties.java`
- Create: `src/main/java/com/ba/analyzer/scheduler/HourlyRiseOiScheduler.java`
- Create: `src/main/java/com/ba/analyzer/controller/HourlyRiseOiController.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/ba/analyzer/controller/HourlyRiseOiControllerTest.java`
- Test: `src/test/java/com/ba/analyzer/scheduler/HourlyRiseOiSchedulerTest.java`

**Interfaces:**
- Produces: `AppProperties.getHourlyScan()` → `HourlyScanConfig { boolean isEnabled(); int getPeriods(); }`
- Produces: `AppProperties.ScheduleConfig.getHourlyScan()`（cron 字符串）
- Produces: `GET /api/market/hourly-rise-oi?periods=4&refresh=false`
  - 200 → `ScanResult`
  - 503 → `{"error":"scan in progress","periods":n}`（`ScanBusyException`）
  - 500 → `{"error":"scan failed","periods":n}`
- Produces: `HourlyRiseOiScheduler.runHourlyScan()`

- [ ] **Step 1: 写失败测试**

`HourlyRiseOiControllerTest`（Mockito，mock `HourlyRiseOiService`）：

```java
package com.ba.analyzer.controller;

import com.ba.analyzer.service.HourlyRiseOiService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HourlyRiseOiControllerTest {

    private static HourlyRiseOiService.ScanResult empty(int periods) {
        return new HourlyRiseOiService.ScanResult(System.currentTimeMillis(), 522, 0, periods, 0, List.of());
    }

    @Test
    void clampsPeriodsAndPassesRefresh() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        when(svc.getLatest(2, true)).thenReturn(empty(2));
        when(svc.getLatest(24, false)).thenReturn(empty(24));

        ResponseEntity<?> lo = new HourlyRiseOiController(svc).hourlyRise(1, true);
        ResponseEntity<?> hi = new HourlyRiseOiController(svc).hourlyRise(999, false);

        assertEquals(200, lo.getStatusCode().value());
        assertEquals(200, hi.getStatusCode().value());
        verify(svc).getLatest(2, true);
        verify(svc).getLatest(24, false);
    }

    @Test
    void returnsEmptyResultNotError() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        when(svc.getLatest(4, false)).thenReturn(empty(4));

        ResponseEntity<?> resp = new HourlyRiseOiController(svc).hourlyRise(4, false);

        assertEquals(200, resp.getStatusCode().value());
        HourlyRiseOiService.ScanResult body = (HourlyRiseOiService.ScanResult) resp.getBody();
        assertNotNull(body);
        assertTrue(body.matches().isEmpty());
    }

    @Test
    void returns503WhenScanBusy() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        when(svc.getLatest(4, true)).thenThrow(new HourlyRiseOiService.ScanBusyException());

        ResponseEntity<?> resp = new HourlyRiseOiController(svc).hourlyRise(4, true);

        assertEquals(503, resp.getStatusCode().value());
    }

    @Test
    void returns500WhenScanFails() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        when(svc.getLatest(4, false)).thenThrow(new IllegalStateException("db down"));

        ResponseEntity<?> resp = new HourlyRiseOiController(svc).hourlyRise(4, false);

        assertEquals(500, resp.getStatusCode().value());
    }
}
```

`HourlyRiseOiSchedulerTest`：

```java
package com.ba.analyzer.scheduler;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.service.HourlyRiseOiService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class HourlyRiseOiSchedulerTest {

    @Test
    void skipsWhenDisabled() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        AppProperties props = new AppProperties();
        props.getHourlyScan().setEnabled(false);

        new HourlyRiseOiScheduler(svc, props).runHourlyScan();

        verifyNoInteractions(svc);
    }

    @Test
    void scansConfiguredPeriods() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        AppProperties props = new AppProperties();
        props.getHourlyScan().setEnabled(true);
        props.getHourlyScan().setPeriods(5);

        new HourlyRiseOiScheduler(svc, props).runHourlyScan();

        verify(svc).scan(5);
    }

    @Test
    void swallowsScanFailure() {
        HourlyRiseOiService svc = mock(HourlyRiseOiService.class);
        when(svc.scan(4)).thenThrow(new IllegalStateException("boom"));
        AppProperties props = new AppProperties();

        new HourlyRiseOiScheduler(svc, props).runHourlyScan();   // 不抛异常
        verify(svc).scan(4);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest='HourlyRiseOiControllerTest,HourlyRiseOiSchedulerTest' test`
Expected: 编译失败（两个类不存在）

- [ ] **Step 3: 实现配置、调度器、Controller**

`AppProperties` 增加：

```java
private HourlyScanConfig hourlyScan = new HourlyScanConfig();

/** 每小时扫描: 最近 N 根已收盘 1h 线连阳 + 同窗口 sum_open_interest 净增。 */
@Data
public static class HourlyScanConfig {
    private boolean enabled = true;
    @Min(2)
    private int periods = 4;
}
```

`ScheduleConfig` 增加字段：`private String hourlyScan = "0 10 * * * ?";`

`HourlyRiseOiScheduler`：

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class HourlyRiseOiScheduler {

    private final HourlyRiseOiService hourlyRiseOiService;
    private final AppProperties appProperties;

    @Scheduled(cron = "${binance.schedule.hourly-scan}")
    public void runHourlyScan() {
        if (!appProperties.getHourlyScan().isEnabled()) return;
        int periods = appProperties.getHourlyScan().getPeriods();
        try {
            HourlyRiseOiService.ScanResult r = hourlyRiseOiService.scan(periods);
            log.info("hourly-rise-oi scheduled scan done: periods={} matched={}", periods, r.matchedCount());
        } catch (Exception e) {
            log.error("hourly-rise-oi scheduled scan failed, keeping previous snapshot", e);
        }
    }
}
```

`HourlyRiseOiController`（`@RestController`，路径写在方法上，避免和 `MarketController` 的 `/api/market` 冲突判定）：

```java
@RestController
@RequiredArgsConstructor
public class HourlyRiseOiController {

    private final HourlyRiseOiService hourlyRiseOiService;

    @GetMapping(value = "/api/market/hourly-rise-oi", produces = "application/json;charset=UTF-8")
    public ResponseEntity<?> hourlyRise(
            @RequestParam(defaultValue = "4") int periods,
            @RequestParam(defaultValue = "false") boolean refresh) {
        int n = HourlyRiseOiService.clampPeriods(periods);
        try {
            return ResponseEntity.ok(hourlyRiseOiService.getLatest(n, refresh));
        } catch (HourlyRiseOiService.ScanBusyException e) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "scan in progress", "periods", n));
        } catch (Exception e) {
            log.error("hourly-rise-oi scan failed: periods={}", n, e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "scan failed", "periods", n));
        }
    }
}
```

`application.yml` 的 `binance` 下增加：

```yaml
  # 每小时扫描: 最近 N 根已收盘 1h 线连阳 + 同窗口 OI 净增 (独立页面, 不进分析器框架)
  hourly-scan:
    enabled: true
    periods: 4
```

`binance.schedule` 下增加：

```yaml
    # 整点后 10 分钟: 等 ba-sync 把上一个整点的 1h OI 写入库 (其 mid-freq 在 :05/:35)
    hourly-scan: "0 10 * * * ?"
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest='HourlyRiseOiControllerTest,HourlyRiseOiSchedulerTest' test`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/ba/analyzer/config/AppProperties.java \
        src/main/java/com/ba/analyzer/scheduler/HourlyRiseOiScheduler.java \
        src/main/java/com/ba/analyzer/controller/HourlyRiseOiController.java \
        src/main/resources/application.yml \
        src/test/java/com/ba/analyzer/controller/HourlyRiseOiControllerTest.java \
        src/test/java/com/ba/analyzer/scheduler/HourlyRiseOiSchedulerTest.java
git commit -m "feat(analysis): expose hourly rise/OI scan endpoint and schedule"
```

---

### Task 4: 前端独立页面

**Files:**
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/main/resources/static/css/style.css`（仅当现有 class 不够）
- Test: 无单测，用 `node --check` + Task 5 的实跑验证

**Interfaces:**
- Consumes: `GET /api/market/hourly-rise-oi`
- Produces: `marketApi.hourlyRise(periods, refresh)`、`renderHourlyRise()`、`loadHourlyRise(refresh)`

- [ ] **Step 1: 加 API 方法**

在 `marketApi` 对象里、`alerts` 之后加：

```js
    async hourlyRise(periods, refresh) {
        const res = await fetch(`${MARKET_BASE}/hourly-rise-oi?periods=${periods || 4}&refresh=${refresh ? 'true' : 'false'}`);
        if (!res.ok) throw new Error(res.status === 503 ? '扫描进行中，请稍后' : 'hourly scan failed');
        return res.json();
    }
```

- [ ] **Step 2: 导航与路由**

`MARKET_ITEMS` 第一项之后插入（放最前面，这是新的主用页面）：

```js
    { view: 'market-hourly-rise', label: '小时连涨OI增' },
```

`renderCurrentView()` 增加清理与分支：

```js
    if (state.hourlyRiseTimer) { clearTimeout(state.hourlyRiseTimer); state.hourlyRiseTimer = null; }
```
```js
        case 'market-hourly-rise': renderHourlyRise(); break;
```

`state` 增加：

```js
    // 小时连涨 OI 增
    hourlyRisePeriod: 4,
    hourlyRiseTimer: null,
```

- [ ] **Step 3: 页面与加载函数**

```js
/* ===== Market: 小时连涨 + OI 净增 ===== */
function renderHourlyRise() {
    const content = document.getElementById('content');
    content.innerHTML = `
        <div class="page-title">小时连涨 OI 增</div>
        <div class="detail-section">
            <div class="flow-hint">
                口径：最近 N 根<strong>已收盘</strong> 1h K 线全部 <code>close &gt; open</code>，
                且同窗口 <code>sum_open_interest</code>（币数）净增。OI 为整点快照，页面展示实际使用的 OI 区间。
                每小时 :10 自动扫描，页面每 60 秒读一次缓存。
            </div>
            <div class="market-toolbar" style="margin-top:10px">
                <label class="toolbar-label">连涨</label>
                <select id="hr-hours" class="toolbar-select">
                    <option value="2">2 小时</option><option value="3">3 小时</option>
                    <option value="4">4 小时</option><option value="5">5 小时</option>
                    <option value="6">6 小时</option>
                </select>
                <button class="btn-primary" id="hr-go">立即扫描</button>
            </div>
            <div id="market-result"><div class="loading">扫描中…</div></div>
        </div>`;
    const sel = document.getElementById('hr-hours');
    sel.value = String(state.hourlyRisePeriod);
    sel.addEventListener('change', () => {
        state.hourlyRisePeriod = parseInt(sel.value, 10) || 4;
        loadHourlyRise(false);
    });
    document.getElementById('hr-go').addEventListener('click', () => loadHourlyRise(true));
    loadHourlyRise(false);
}

async function loadHourlyRise(refresh) {
    if (state.currentView !== 'market-hourly-rise') return;
    const box = document.getElementById('market-result');
    if (!box) return;
    if (state.hourlyRiseTimer) { clearTimeout(state.hourlyRiseTimer); state.hourlyRiseTimer = null; }
    try {
        const r = await marketApi.hourlyRise(state.hourlyRisePeriod, refresh);
        const scanned = new Date(r.scannedAt).toLocaleString('zh-CN');
        if (!r.matches || r.matches.length === 0) {
            box.innerHTML = `<div class="result-summary">扫描 ${r.totalSymbols} 个币 · 无命中 · 扫描于 ${scanned}</div>
                <div class="empty-state">没有符合条件的币种</div>`;
        } else {
            const trs = r.matches.map(m => {
                const end = new Date(m.oiEndTime).toLocaleString('zh-CN', { hour12: false });
                const span = ((m.oiEndTime - m.oiStartTime) / 3600000).toFixed(0);
                return `<tr class="hr-row" data-symbol="${escHtml(m.symbol)}" style="cursor:pointer">
                    <td><strong>${escHtml(m.symbol)}</strong></td>
                    <td>${fmtNum(m.price)}</td>
                    <td>${pctSpan(m.priceChangePercent * 100, 2)}</td>
                    <td>${pctSpan(m.oiChangePercent * 100, 2)}</td>
                    <td>${m.periods}h</td>
                    <td>${span}h</td>
                    <td>${fmtVol(m.quoteVolume24h)}</td>
                    <td>${end}</td>
                </tr>`;
            }).join('');
            box.innerHTML = `
                <div class="result-summary">共 ${r.matchedCount} 个命中 · 扫描 ${r.totalSymbols} 个币 ·
                    OI 缺失 ${r.skippedNoOi} 个 · 扫描于 ${scanned} · 点行看资金流</div>
                <div class="result-table-wrap"><table class="result-table">
                    <thead><tr><th>币种</th><th>现价</th><th>窗口涨幅</th><th>OI 变化</th>
                    <th>连涨</th><th>OI 区间</th><th>24h 成交额</th><th>OI 截止</th></tr></thead>
                    <tbody>${trs}</tbody></table></div>`;
            box.querySelectorAll('.hr-row').forEach(tr => tr.addEventListener('click', () => {
                state.flowSymbol = tr.dataset.symbol;
                state.currentView = 'market-flow';
                renderSidebar();
                renderCurrentView();
            }));
        }
    } catch (e) {
        box.innerHTML = `<div class="error-msg">加载失败: ${escHtml(String(e.message || e))}</div>`;
    }
    state.hourlyRiseTimer = setTimeout(() => loadHourlyRise(false), 60000);
}
```

样式只在缺配色时补：`pctSpan` 已产出 `positive/negative/neutral`，先不新增 CSS；若页面表格样式已由 `.result-table` 覆盖即可。

- [ ] **Step 4: 校验语法**

Run: `node --check src/main/resources/static/js/app.js`
Expected: 无输出（退出码 0）

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/static/js/app.js src/main/resources/static/css/style.css
git commit -m "feat(web): add hourly rise/OI scan page"
```

---

### Task 5: 配置同步、文档与端到端验证

**Files:**
- Modify: `config/application.yml.example`
- Modify: `README.md`
- Modify: `CLAUDE.md`（若有接口/页面清单）
- Modify: `docs/superpowers/specs/2026-11-10-hourly-rise-oi-design.md`（状态改为已批准；5.9 的 ba-sync 建议标注已实现）

**Interfaces:**
- Consumes: 前四个任务的全部产出

- [ ] **Step 1: 配置模板同步**

`config/application.yml.example` 的 `binance` 下增加与 `application.yml` 相同的 `hourly-scan` 段，`schedule` 下增加 `hourly-scan: "0 10 * * * ?"`。本地 `config/application.yml`（不入库）同步加一份，供本地实跑使用。

- [ ] **Step 2: 文档**

`README.md`：
- 职责列表加一行：`行情查询接口 ... 小时连涨 OI 增独立扫描（/api/market/hourly-rise-oi）`
- 定时任务表加一行：`| hourly-scan | "0 10 * * * ?" | 每小时扫描最近 N 根已收盘 1h 连阳且同窗口 sum_open_interest 净增，页面实时读缓存 |`

`CLAUDE.md`：在 `MarketController (/api/market)` 的接口列表末尾补一条：
`- GET /api/market/hourly-rise-oi — hourly scan: last N closed 1h bars all close>open and sum_open_interest net up in the same window (independent page, cached in memory).`
（该接口由独立 Controller 提供，但路径在 `/api/market` 下，写进这段清单最容易被找到。）

设计稿：状态改「已批准」；5.9 的「ba-sync 侧（建议，可选）」改为「已实现（ba-sync 已把 1h OI 门控收紧到 1 个 period，cron 改为 :05/:35）」。

- [ ] **Step 3: 全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS，全部测试通过（含新增 21 个用例）

- [ ] **Step 4: 本地实跑验证**

用 8083 起一份，避开正在跑的 8082（生产 443 需要权限，这里显式关 SSL）。本机 `config/application.yml` 指向
`127.0.0.1`（部署模板），实跑要显式覆盖成远端库：

```bash
mvn -q -DskipTests package
java -jar target/ba-analysis-1.0.0.jar \
  --spring.config.additional-location=optional:file:./config/ \
  --server.port=8083 --server.ssl.enabled=false \
  "--spring.datasource.url=jdbc:mysql://43.155.216.33:3306/ba?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai" \
  "--spring.datasource.password=<生产库密码>"
```

依次确认：

```bash
# 1) 接口可用且 periods/refresh 生效
curl -s 'http://127.0.0.1:8083/api/market/hourly-rise-oi?periods=4&refresh=true' | head -c 600

# 2) 空结果不是错误（换个不可能成立的参数区间看结构）
curl -s -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:8083/api/market/hourly-rise-oi?periods=24'

# 3) 静态页面脚本能加载
curl -s -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:8083/js/app.js'
```

expected: 1) 返回 `scannedAt/totalSymbols/matchedCount/periods/skippedNoOi/matches`；2) `200`；3) `200`。

再挑 1 个命中币手工核对（把 `<SYMBOL>` 换成接口返回的币）：

```sql
-- 最近 4 根已收盘 1h 线，应全部 close > open
SELECT open_time, open, close FROM klines
WHERE symbol='<SYMBOL>' AND `interval`='1h'
ORDER BY open_time DESC LIMIT 5;

-- 窗口起止的 sum_open_interest（币数），end 应大于 start
SELECT timestamp, sum_open_interest, sum_open_interest_value FROM open_interest
WHERE symbol='<SYMBOL>' AND period='1h'
ORDER BY timestamp DESC LIMIT 6;
```

expected: K 线 4 根 `close > open`；`sum_open_interest` 末端大于起点（名义值不参与判断）。

- [ ] **Step 5: 提交**

```bash
git add config/application.yml.example README.md CLAUDE.md docs/superpowers/specs/2026-11-10-hourly-rise-oi-design.md
git commit -m "docs: document hourly rise/OI scan page and schedule"
```
