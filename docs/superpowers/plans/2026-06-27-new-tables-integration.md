# New-Tables Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect ba-analysis to the remote DB and use its new tables — switch the symbol universe to the `symbols` table, expose read-only query/snapshot APIs over `long_short_ratio` / `ticker_24h` / `premium_index`, and add a default-on "多空比异动" analyzer.

**Architecture:** Keep the existing read-only three-layer flow (`JdbcDataStore` raw SQL → `DataFetchService` wrappers → analyzers/controllers). Add three new models with String fields + numeric getters (mirroring `OpenInterestData`). New analyzer implements `Analyzer` directly (like `CompositeScoreAnalyzer`), runs in the existing 5-minute short-term batch. New `MarketController` is pure passthrough + sort/limit, no scheduling.

**Tech Stack:** Spring Boot 3.2 / Java 21, spring-jdbc (`JdbcTemplate`), Lombok, JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-06-27-new-tables-integration-design.md`

**Test convention (important):** This codebase unit-tests logic layers with Mockito (mock `DataFetchService`/`AppProperties`/`JdbcDataStore`, hand-build data lists). `JdbcDataStore`'s raw SQL has **no unit tests** and there is **no H2 dependency** — do not add one. Verify the SQL against the live remote DB by running the app (Task 15).

**Build/test commands:**
- All tests: `mvn test`
- Single class: `mvn test -Dtest=LongShortRatioAnalyzerTest`
- Single method: `mvn test -Dtest=LongShortRatioAnalyzerTest#analyze_matchesSmartMoneyAccumulation`
- Build jar: `mvn package -DskipTests`

---

## File Structure

**Create:**
- `src/main/java/com/ba/analyzer/model/LongShortRatioData.java` — multi-空比 row model
- `src/main/java/com/ba/analyzer/model/Ticker24hData.java` — 24h ticker snapshot model
- `src/main/java/com/ba/analyzer/model/PremiumIndexData.java` — mark-price/funding snapshot model
- `src/main/java/com/ba/analyzer/controller/MarketController.java` — read-only query/snapshot API
- `src/main/java/com/ba/analyzer/analysis/LongShortRatioAnalyzer.java` — 多空比异动 analyzer
- `src/test/java/com/ba/analyzer/model/LongShortRatioDataTest.java`
- `src/test/java/com/ba/analyzer/service/SymbolServiceTest.java`
- `src/test/java/com/ba/analyzer/controller/MarketControllerTest.java`
- `src/test/java/com/ba/analyzer/analysis/LongShortRatioAnalyzerTest.java`

**Modify:**
- `src/main/resources/application.yml` — datasource host + new analyzer config block
- `src/main/java/com/ba/analyzer/service/JdbcDataStore.java` — new read methods + row mappers
- `src/main/java/com/ba/analyzer/service/DataFetchService.java` — LSR batch wrapper
- `src/main/java/com/ba/analyzer/service/SymbolService.java` — symbols-table source + klines fallback
- `src/main/java/com/ba/analyzer/config/AppProperties.java` — `LongShortRatioConfig` + wiring
- `src/main/java/com/ba/analyzer/report/ReportWriter.java` — NAME_MAPPING entry
- `src/main/java/com/ba/analyzer/controller/AnalysisController.java` — getParamSpec branch

**Deviation from spec (YAGNI):** Spec 0.3 listed `getTradingSymbols(): List<SymbolInfo>`. Nothing in this scope consumes the metadata, so we expose `getTradingSymbolNames(): List<String>` instead. `SymbolInfo` model is left untouched.

---

## Phase 0 · Foundation

### Task 1: Switch datasource host to remote

**Files:**
- Modify: `src/main/resources/application.yml:13`

- [ ] **Step 1: Edit the host**

In `src/main/resources/application.yml`, change the datasource URL host from `192.168.193.128` to `43.155.216.33`. Final line:

```yaml
    url: jdbc:mysql://43.155.216.33:3306/ba?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
```

Leave `username`, `password`, and all other params unchanged.

- [ ] **Step 2: Verify it still compiles/packages**

Run: `mvn package -DskipTests`
Expected: BUILD SUCCESS (runtime DB connectivity is verified later in Task 15).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "config: point datasource at remote DB 43.155.216.33"
```

---

### Task 2: `LongShortRatioData` model

**Files:**
- Create: `src/main/java/com/ba/analyzer/model/LongShortRatioData.java`
- Test: `src/test/java/com/ba/analyzer/model/LongShortRatioDataTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.ba.analyzer.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LongShortRatioDataTest {

    @Test
    void numericGettersParseStrings() {
        LongShortRatioData d = new LongShortRatioData();
        d.setLongShortRatio("1.85");
        d.setLongValue("0.65");
        d.setShortValue("0.35");
        assertEquals(1.85, d.getRatioValue(), 1e-9);
        assertEquals(0.65, d.getLongValueNum(), 1e-9);
        assertEquals(0.35, d.getShortValueNum(), 1e-9);
    }

    @Test
    void numericGettersReturnZeroOnNullOrBlank() {
        LongShortRatioData d = new LongShortRatioData();
        assertEquals(0.0, d.getRatioValue(), 1e-9);
        d.setLongShortRatio("");
        assertEquals(0.0, d.getRatioValue(), 1e-9);
        d.setLongShortRatio("not-a-number");
        assertEquals(0.0, d.getRatioValue(), 1e-9);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=LongShortRatioDataTest`
Expected: FAIL — `LongShortRatioData` does not exist (compilation error).

- [ ] **Step 3: Write the model**

```java
package com.ba.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 多空比数据模型 (对应 long_short_ratio 表)。
 * ratioType: GLOBAL_ACCOUNT / TOP_ACCOUNT / TOP_POSITION / TAKER。
 * 比率字段存 String, 由 getter 解析 double, 解析失败返回 0 (参照 OpenInterestData)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LongShortRatioData {

    private String symbol;
    private String ratioType;
    private String period;
    private long timestamp;
    private String longShortRatio;
    private String longValue;
    private String shortValue;

    public double getRatioValue() { return parse(longShortRatio); }

    public double getLongValueNum() { return parse(longValue); }

    public double getShortValueNum() { return parse(shortValue); }

    private static double parse(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=LongShortRatioDataTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ba/analyzer/model/LongShortRatioData.java src/test/java/com/ba/analyzer/model/LongShortRatioDataTest.java
git commit -m "feat: add LongShortRatioData model"
```

---

### Task 3: `Ticker24hData` and `PremiumIndexData` models

**Files:**
- Create: `src/main/java/com/ba/analyzer/model/Ticker24hData.java`
- Create: `src/main/java/com/ba/analyzer/model/PremiumIndexData.java`
- Test: extend `src/test/java/com/ba/analyzer/model/LongShortRatioDataTest.java` is NOT reused; add assertions in a new test below.
- Test: `src/test/java/com/ba/analyzer/model/SnapshotModelsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.ba.analyzer.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotModelsTest {

    @Test
    void ticker24hNumericGetters() {
        Ticker24hData t = new Ticker24hData();
        t.setPriceChangePercent("7.623");
        t.setLastPrice("123.45");
        t.setQuoteVolume("9876543.21");
        assertEquals(7.623, t.getPriceChangePercentNum(), 1e-9);
        assertEquals(123.45, t.getLastPriceNum(), 1e-9);
        assertEquals(9876543.21, t.getQuoteVolumeNum(), 1e-6);
        // blank/invalid -> 0
        Ticker24hData empty = new Ticker24hData();
        assertEquals(0.0, empty.getPriceChangePercentNum(), 1e-9);
    }

    @Test
    void premiumIndexNumericGetters() {
        PremiumIndexData p = new PremiumIndexData();
        p.setMarkPrice("100.5");
        p.setLastFundingRate("-0.00012345");
        assertEquals(100.5, p.getMarkPriceNum(), 1e-9);
        assertEquals(-0.00012345, p.getLastFundingRateNum(), 1e-12);
        PremiumIndexData empty = new PremiumIndexData();
        assertEquals(0.0, empty.getLastFundingRateNum(), 1e-12);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SnapshotModelsTest`
Expected: FAIL — models do not exist (compilation error).

- [ ] **Step 3: Write `Ticker24hData`**

```java
package com.ba.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 24h 行情快照模型 (对应 ticker_24h 表, 每币一行最新)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Ticker24hData {

    private String symbol;
    private String priceChangePercent;
    private String lastPrice;
    private String openPrice;
    private String highPrice;
    private String lowPrice;
    private String weightedAvgPrice;
    private String volume;
    private String quoteVolume;
    private long tradeCount;
    private long openTime;
    private long closeTime;
    private long capturedAt;

    public double getPriceChangePercentNum() { return parse(priceChangePercent); }

    public double getLastPriceNum() { return parse(lastPrice); }

    public double getQuoteVolumeNum() { return parse(quoteVolume); }

    private static double parse(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
```

- [ ] **Step 4: Write `PremiumIndexData`**

```java
package com.ba.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 标记价/溢价指数快照模型 (对应 premium_index 表, 每币一行最新)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PremiumIndexData {

    private String symbol;
    private String markPrice;
    private String indexPrice;
    private String estimatedSettlePrice;
    private String lastFundingRate;
    private long nextFundingTime;
    private String interestRate;
    private long time;

    public double getMarkPriceNum() { return parse(markPrice); }

    public double getLastFundingRateNum() { return parse(lastFundingRate); }

    private static double parse(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=SnapshotModelsTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/ba/analyzer/model/Ticker24hData.java src/main/java/com/ba/analyzer/model/PremiumIndexData.java src/test/java/com/ba/analyzer/model/SnapshotModelsTest.java
git commit -m "feat: add Ticker24hData and PremiumIndexData snapshot models"
```

---

### Task 4: `JdbcDataStore` read methods

No unit test (consistent with existing `JdbcDataStore`, which has none, and no H2 dep). Verified at runtime in Task 15.

**Files:**
- Modify: `src/main/java/com/ba/analyzer/service/JdbcDataStore.java`

- [ ] **Step 1: Add imports**

At the top of `JdbcDataStore.java`, add to the existing import block:

```java
import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.model.Ticker24hData;
import com.ba.analyzer.model.PremiumIndexData;
import java.util.ArrayList;
```

- [ ] **Step 2: Add the read methods**

Insert before the `// ======================== Row Mappers ========================` section:

```java
    // ======================== Long/Short Ratio (read-only) ========================

    public List<LongShortRatioData> getLongShortRatio(String symbol, String ratioType, String period, int limit) {
        String sql = "SELECT * FROM long_short_ratio WHERE symbol=? AND ratio_type=? AND period=? " +
                "ORDER BY `timestamp` DESC LIMIT ?";
        List<LongShortRatioData> result = jdbc.query(sql, this::mapLsr, symbol, ratioType, period, limit);
        Collections.reverse(result); // 时间升序, 末尾为最新 (与 getKlines 一致)
        return result;
    }

    public Map<String, List<LongShortRatioData>> getLongShortRatioBatch(
            List<String> symbols, String ratioType, String period, int limit) {
        Map<String, List<LongShortRatioData>> result = new HashMap<>();
        for (String symbol : symbols) {
            List<LongShortRatioData> rows = getLongShortRatio(symbol, ratioType, period, limit);
            if (!rows.isEmpty()) result.put(symbol, rows);
        }
        return result;
    }

    /** 全市场每个 symbol 取该 ratioType+period 的最新一条 (供 ranking 接口)。 */
    public List<LongShortRatioData> getLatestLongShortRatioAll(String ratioType, String period) {
        String sql = "SELECT l.* FROM long_short_ratio l " +
                "JOIN (SELECT symbol, MAX(`timestamp`) mt FROM long_short_ratio " +
                "      WHERE ratio_type=? AND period=? GROUP BY symbol) g " +
                "ON l.symbol=g.symbol AND l.`timestamp`=g.mt " +
                "WHERE l.ratio_type=? AND l.period=?";
        return jdbc.query(sql, this::mapLsr, ratioType, period, ratioType, period);
    }

    // ======================== Ticker 24h (read-only snapshot) ========================

    public List<Ticker24hData> getAllTicker24h() {
        return jdbc.query("SELECT * FROM ticker_24h", this::mapTicker);
    }

    public Ticker24hData getTicker24h(String symbol) {
        List<Ticker24hData> rows = jdbc.query(
                "SELECT * FROM ticker_24h WHERE symbol=?", this::mapTicker, symbol);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ======================== Premium Index (read-only snapshot) ========================

    public List<PremiumIndexData> getAllPremiumIndex() {
        return jdbc.query("SELECT * FROM premium_index", this::mapPremium);
    }

    public PremiumIndexData getPremiumIndex(String symbol) {
        List<PremiumIndexData> rows = jdbc.query(
                "SELECT * FROM premium_index WHERE symbol=?", this::mapPremium, symbol);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ======================== Symbols (read-only) ========================

    /** 在交易的 USDT 永续合约名 (规范来源, 供 SymbolService)。 */
    public List<String> getTradingSymbolNames() {
        return jdbc.queryForList(
                "SELECT symbol FROM symbols WHERE status='TRADING' AND quote_asset='USDT' ORDER BY symbol",
                String.class);
    }

    /** 兜底: 从 klines 推导币种 (symbols 表为空时用)。 */
    public List<String> getSymbolNamesFromKlines() {
        return jdbc.queryForList(
                "SELECT DISTINCT symbol FROM klines WHERE `interval`='1d' ORDER BY symbol",
                String.class);
    }
```

- [ ] **Step 3: Add the row mappers**

Inside the `// ===== Row Mappers =====` section (after `mapOi`), add:

```java
    private LongShortRatioData mapLsr(ResultSet rs, int rowNum) throws SQLException {
        LongShortRatioData d = new LongShortRatioData();
        d.setSymbol(rs.getString("symbol"));
        d.setRatioType(rs.getString("ratio_type"));
        d.setPeriod(rs.getString("period"));
        d.setTimestamp(rs.getLong("timestamp"));
        d.setLongShortRatio(rs.getString("long_short_ratio"));
        d.setLongValue(rs.getString("long_value"));
        d.setShortValue(rs.getString("short_value"));
        return d;
    }

    private Ticker24hData mapTicker(ResultSet rs, int rowNum) throws SQLException {
        Ticker24hData t = new Ticker24hData();
        t.setSymbol(rs.getString("symbol"));
        t.setPriceChangePercent(rs.getString("price_change_percent"));
        t.setLastPrice(rs.getString("last_price"));
        t.setOpenPrice(rs.getString("open_price"));
        t.setHighPrice(rs.getString("high_price"));
        t.setLowPrice(rs.getString("low_price"));
        t.setWeightedAvgPrice(rs.getString("weighted_avg_price"));
        t.setVolume(rs.getString("volume"));
        t.setQuoteVolume(rs.getString("quote_volume"));
        t.setTradeCount(rs.getLong("trade_count"));
        t.setOpenTime(rs.getLong("open_time"));
        t.setCloseTime(rs.getLong("close_time"));
        t.setCapturedAt(rs.getLong("captured_at"));
        return t;
    }

    private PremiumIndexData mapPremium(ResultSet rs, int rowNum) throws SQLException {
        PremiumIndexData p = new PremiumIndexData();
        p.setSymbol(rs.getString("symbol"));
        p.setMarkPrice(rs.getString("mark_price"));
        p.setIndexPrice(rs.getString("index_price"));
        p.setEstimatedSettlePrice(rs.getString("estimated_settle_price"));
        p.setLastFundingRate(rs.getString("last_funding_rate"));
        p.setNextFundingTime(rs.getLong("next_funding_time"));
        p.setInterestRate(rs.getString("interest_rate"));
        p.setTime(rs.getLong("time"));
        return p;
    }
```

- [ ] **Step 4: Verify it compiles**

Run: `mvn test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ba/analyzer/service/JdbcDataStore.java
git commit -m "feat: add JdbcDataStore reads for LSR, ticker_24h, premium_index, symbols"
```

---

### Task 5: `DataFetchService` LSR wrapper

**Files:**
- Modify: `src/main/java/com/ba/analyzer/service/DataFetchService.java`

No new test (thin passthrough; exercised by the analyzer test in Task 9 via mocks). Keep the existing `<90%` warn pattern.

- [ ] **Step 1: Add imports**

Add to the import block:

```java
import com.ba.analyzer.model.LongShortRatioData;
```

- [ ] **Step 2: Add the wrapper method**

Insert after `getRecentFundingRates(...)`:

```java
    /** 批量读多空比 (供分析器)。缺失不放入 map; 覆盖率 <90% 时打 warn。 */
    public Map<String, List<LongShortRatioData>> getLongShortRatioBatch(
            List<String> symbols, String ratioType, String period, int limit) {
        Map<String, List<LongShortRatioData>> stored =
                dataStore.getLongShortRatioBatch(symbols, ratioType, period, limit);
        if (stored.size() < symbols.size() * 0.9) {
            log.warn("DB has {} LSR for only {}/{} symbols; sync service may be behind",
                    ratioType, stored.size(), symbols.size());
        }
        return stored;
    }
```

- [ ] **Step 3: Verify it compiles**

Run: `mvn test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/ba/analyzer/service/DataFetchService.java
git commit -m "feat: add LSR batch wrapper to DataFetchService"
```

---

## Phase A · SymbolService data source

### Task 6: SymbolService reads `symbols` table with klines fallback

**Files:**
- Modify: `src/main/java/com/ba/analyzer/service/SymbolService.java`
- Test: `src/test/java/com/ba/analyzer/service/SymbolServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.ba.analyzer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SymbolServiceTest {

    @Mock
    private JdbcDataStore dataStore;

    @InjectMocks
    private SymbolService symbolService;

    @Test
    void usesSymbolsTableWhenAvailable() {
        when(dataStore.getTradingSymbolNames()).thenReturn(List.of("BTCUSDT", "ETHUSDT"));
        List<String> result = symbolService.getSymbolNames();
        assertEquals(List.of("BTCUSDT", "ETHUSDT"), result);
        verify(dataStore, never()).getSymbolNamesFromKlines();
    }

    @Test
    void fallsBackToKlinesWhenSymbolsEmpty() {
        when(dataStore.getTradingSymbolNames()).thenReturn(List.of());
        when(dataStore.getSymbolNamesFromKlines()).thenReturn(List.of("SOLUSDT"));
        List<String> result = symbolService.getSymbolNames();
        assertEquals(List.of("SOLUSDT"), result);
    }

    @Test
    void returnsEmptyWhenBothEmpty() {
        when(dataStore.getTradingSymbolNames()).thenReturn(List.of());
        when(dataStore.getSymbolNamesFromKlines()).thenReturn(List.of());
        assertTrue(symbolService.getSymbolNames().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SymbolServiceTest`
Expected: FAIL — `SymbolService` still takes `JdbcTemplate`, has no `JdbcDataStore` ctor; `@InjectMocks` cannot wire / methods missing.

- [ ] **Step 3: Rewrite `SymbolService`**

Replace the entire body of `src/main/java/com/ba/analyzer/service/SymbolService.java` with:

```java
package com.ba.analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 合约列表服务 (ba-analysis 专用) —— 从MySQL推导币种全集, 不访问币安、不读本地文件。
 *
 * 主源: symbols 表中 status='TRADING' 且 quote_asset='USDT' 的合约 (规范来源)。
 * 兜底: symbols 表为空时回退到 klines 去重 (同步服务尚未灌 symbols 时不致全停)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SymbolService {

    private final JdbcDataStore dataStore;

    public List<String> getSymbolNames() {
        List<String> symbols = dataStore.getTradingSymbolNames();
        if (!symbols.isEmpty()) return symbols;

        log.warn("symbols table empty; falling back to DISTINCT symbol FROM klines");
        List<String> fromKlines = dataStore.getSymbolNamesFromKlines();
        if (fromKlines.isEmpty()) {
            log.warn("No symbols in klines either; sync service (ba-sync) may not have run yet");
        }
        return fromKlines;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=SymbolServiceTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ba/analyzer/service/SymbolService.java src/test/java/com/ba/analyzer/service/SymbolServiceTest.java
git commit -m "feat: SymbolService reads symbols table with klines fallback"
```

---

## Phase B · MarketController query/snapshot API

### Task 7: MarketController — LSR endpoints

**Files:**
- Create: `src/main/java/com/ba/analyzer/controller/MarketController.java`
- Test: `src/test/java/com/ba/analyzer/controller/MarketControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.ba.analyzer.controller;

import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.service.JdbcDataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketControllerTest {

    @Mock
    private JdbcDataStore store;

    @InjectMocks
    private MarketController controller;

    private LongShortRatioData lsr(String symbol, double ratio) {
        LongShortRatioData d = new LongShortRatioData();
        d.setSymbol(symbol);
        d.setLongShortRatio(String.valueOf(ratio));
        return d;
    }

    @Test
    void lsrRankingSortsDescAndLimits() {
        when(store.getLatestLongShortRatioAll("TOP_POSITION", "1h"))
                .thenReturn(new java.util.ArrayList<>(List.of(
                        lsr("A", 1.0), lsr("B", 3.0), lsr("C", 2.0))));
        ResponseEntity<?> resp = controller.lsrRanking("TOP_POSITION", "1h", 2);
        assertEquals(200, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<LongShortRatioData> body = (List<LongShortRatioData>) resp.getBody();
        assertEquals(2, body.size());
        assertEquals("B", body.get(0).getSymbol());
        assertEquals("C", body.get(1).getSymbol());
    }

    @Test
    void lsrRankingRejectsInvalidType() {
        ResponseEntity<?> resp = controller.lsrRanking("BOGUS", "1h", 50);
        assertEquals(400, resp.getStatusCode().value());
        verify(store, never()).getLatestLongShortRatioAll(any(), any());
    }

    @Test
    void lsrBySymbolReturnsAllFourTypes() {
        when(store.getLongShortRatio(eq("BTCUSDT"), anyString(), eq("1h"), anyInt()))
                .thenReturn(List.of(lsr("BTCUSDT", 1.5)));
        ResponseEntity<Map<String, List<LongShortRatioData>>> resp =
                controller.lsr("BTCUSDT", "1h", 48);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(4, resp.getBody().size());
        assertTrue(resp.getBody().containsKey("TOP_POSITION"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MarketControllerTest`
Expected: FAIL — `MarketController` does not exist.

- [ ] **Step 3: Create `MarketController` with LSR endpoints**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=MarketControllerTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ba/analyzer/controller/MarketController.java src/test/java/com/ba/analyzer/controller/MarketControllerTest.java
git commit -m "feat: MarketController LSR query and ranking endpoints"
```

---

### Task 8: MarketController — ticker_24h and premium_index snapshot endpoints

**Files:**
- Modify: `src/main/java/com/ba/analyzer/controller/MarketController.java`
- Modify: `src/test/java/com/ba/analyzer/controller/MarketControllerTest.java`

- [ ] **Step 1: Add failing tests**

Add these helpers and tests to `MarketControllerTest`:

```java
    private com.ba.analyzer.model.Ticker24hData ticker(String symbol, double chgPct, double quoteVol) {
        com.ba.analyzer.model.Ticker24hData t = new com.ba.analyzer.model.Ticker24hData();
        t.setSymbol(symbol);
        t.setPriceChangePercent(String.valueOf(chgPct));
        t.setQuoteVolume(String.valueOf(quoteVol));
        return t;
    }

    private com.ba.analyzer.model.PremiumIndexData premium(String symbol, double fundingRate) {
        com.ba.analyzer.model.PremiumIndexData p = new com.ba.analyzer.model.PremiumIndexData();
        p.setSymbol(symbol);
        p.setLastFundingRate(String.valueOf(fundingRate));
        return p;
    }

    @Test
    void tickerGainersSortedDescByChangePct() {
        when(store.getAllTicker24h()).thenReturn(new java.util.ArrayList<>(List.of(
                ticker("A", 1.0, 10), ticker("B", 9.0, 10), ticker("C", 5.0, 10))));
        ResponseEntity<List<com.ba.analyzer.model.Ticker24hData>> resp = controller.tickerGainers(2);
        assertEquals("B", resp.getBody().get(0).getSymbol());
        assertEquals("C", resp.getBody().get(1).getSymbol());
        assertEquals(2, resp.getBody().size());
    }

    @Test
    void tickerLosersSortedAscByChangePct() {
        when(store.getAllTicker24h()).thenReturn(new java.util.ArrayList<>(List.of(
                ticker("A", 1.0, 10), ticker("B", -9.0, 10), ticker("C", 5.0, 10))));
        ResponseEntity<List<com.ba.analyzer.model.Ticker24hData>> resp = controller.tickerLosers(1);
        assertEquals("B", resp.getBody().get(0).getSymbol());
        assertEquals(1, resp.getBody().size());
    }

    @Test
    void tickerVolumeSortedDescByQuoteVolume() {
        when(store.getAllTicker24h()).thenReturn(new java.util.ArrayList<>(List.of(
                ticker("A", 1.0, 100), ticker("B", 1.0, 999), ticker("C", 1.0, 50))));
        ResponseEntity<List<com.ba.analyzer.model.Ticker24hData>> resp = controller.tickerVolume(2);
        assertEquals("B", resp.getBody().get(0).getSymbol());
    }

    @Test
    void tickerBySymbol404WhenMissing() {
        when(store.getTicker24h("NOPEUSDT")).thenReturn(null);
        ResponseEntity<com.ba.analyzer.model.Ticker24hData> resp = controller.ticker("NOPEUSDT");
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void fundingRankingDescByFundingRate() {
        when(store.getAllPremiumIndex()).thenReturn(new java.util.ArrayList<>(List.of(
                premium("A", 0.0001), premium("B", 0.0009), premium("C", -0.0003))));
        ResponseEntity<List<com.ba.analyzer.model.PremiumIndexData>> resp =
                controller.fundingRanking(2, "desc");
        assertEquals("B", resp.getBody().get(0).getSymbol());
        assertEquals("A", resp.getBody().get(1).getSymbol());
    }

    @Test
    void fundingRankingAscByFundingRate() {
        when(store.getAllPremiumIndex()).thenReturn(new java.util.ArrayList<>(List.of(
                premium("A", 0.0001), premium("B", 0.0009), premium("C", -0.0003))));
        ResponseEntity<List<com.ba.analyzer.model.PremiumIndexData>> resp =
                controller.fundingRanking(1, "asc");
        assertEquals("C", resp.getBody().get(0).getSymbol());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MarketControllerTest`
Expected: FAIL — `tickerGainers`/`tickerLosers`/`tickerVolume`/`ticker`/`fundingRanking` not defined.

- [ ] **Step 3: Add the endpoints to `MarketController`**

Append before the closing brace of `MarketController`:

```java
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
        List<Ticker24hData> all = store.getAllTicker24h();
        all.sort(cmp);
        return all.stream().limit(clampLimit(limit)).toList();
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
        List<PremiumIndexData> all = store.getAllPremiumIndex();
        Comparator<PremiumIndexData> cmp =
                Comparator.comparingDouble(PremiumIndexData::getLastFundingRateNum);
        if (!"asc".equalsIgnoreCase(order)) cmp = cmp.reversed();
        all.sort(cmp);
        return ResponseEntity.ok(all.stream().limit(clampLimit(limit)).toList());
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=MarketControllerTest`
Expected: PASS (9 tests total).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ba/analyzer/controller/MarketController.java src/test/java/com/ba/analyzer/controller/MarketControllerTest.java
git commit -m "feat: MarketController ticker_24h and premium_index snapshot endpoints"
```

---

## Phase C · 多空比异动 Analyzer

### Task 9: `LongShortRatioConfig` in AppProperties + yml block

**Files:**
- Modify: `src/main/java/com/ba/analyzer/config/AppProperties.java`
- Modify: `src/main/resources/application.yml`

No unit test (config POJO; defaults exercised by the analyzer test in Task 10).

- [ ] **Step 1: Add the config class**

In `AppProperties.java`, inside `AnalysisConfig`, add the field after `compositeScore`:

```java
        private LongShortRatioConfig longShortRatio = new LongShortRatioConfig();
```

Then add the nested class (place it after `CompositeScoreConfig`):

```java
    @Data
    public static class LongShortRatioConfig {
        private boolean enabled = true;     // 默认启用, 随 short-term 批次每5分钟跑
        private String period = "1h";       // LSR 统计周期 (库内默认 1h)
        private int lookback = 12;           // 与多少根之前比较 (变化率/背离)
        private int minScore = 60;           // 低于此分不输出
        // 维度权重 (合计 100)
        private int topPositionWeight = 40;  // 大户持仓多空比抬升
        private int takerWeight = 35;        // taker 主动买盘占优
        private int divergenceWeight = 25;   // 大户进 + 散户退 背离
        private double takerBullishMin = 1.0; // taker 买卖比超过此值算买盘占优
    }
```

- [ ] **Step 2: Add the yml block**

In `application.yml`, under `binance.analysis:`, add after the `composite-score:` block:

```yaml
    long-short-ratio:
      enabled: true
      period: 1h
      lookback: 12
      min-score: 60
      top-position-weight: 40
      taker-weight: 35
      divergence-weight: 25
      taker-bullish-min: 1.0
```

- [ ] **Step 3: Verify it compiles**

Run: `mvn test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/ba/analyzer/config/AppProperties.java src/main/resources/application.yml
git commit -m "feat: add LongShortRatioConfig and yml block"
```

---

### Task 10: `LongShortRatioAnalyzer`

**Files:**
- Create: `src/main/java/com/ba/analyzer/analysis/LongShortRatioAnalyzer.java`
- Test: `src/test/java/com/ba/analyzer/analysis/LongShortRatioAnalyzerTest.java`

Scoring (each sub-score is a 0–1 hit × its weight, total capped at 100):
- **topPositionHit** = `clamp01(tpChangePct / 10.0)` where `tpChangePct = (latestTP − agoTP)/agoTP×100`. +10% over the lookback window = full.
- **takerHit** = `clamp01((latestTaker − takerBullishMin) / 1.0)`. taker 1.0→0, 2.0→full.
- **divergenceHit** = if `tpChangePct > 0 && gaChangePct < 0` then `clamp01((tpChangePct − gaChangePct) / 20.0)` else 0. (大户多空比上行 + 散户账户多空比下行.)
- **Skip the symbol** (no match) if TOP_POSITION series has `< lookback+1` points OR TAKER series is empty. GLOBAL_ACCOUNT missing → divergence = 0 (graceful).
- **side** = `"long"` when `tpChangePct > 0 && latestTaker >= takerBullishMin`, else `"watch"`.

- [ ] **Step 1: Write the failing test**

```java
package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.service.DataFetchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LongShortRatioAnalyzerTest {

    @Mock
    private DataFetchService dataFetchService;
    @Mock
    private AppProperties appProperties;

    private LongShortRatioAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        AppProperties.AnalysisConfig analysisConfig = new AppProperties.AnalysisConfig();
        analysisConfig.setLongShortRatio(new AppProperties.LongShortRatioConfig()); // defaults
        when(appProperties.getAnalysis()).thenReturn(analysisConfig);
        analyzer = new LongShortRatioAnalyzer(dataFetchService, appProperties);
    }

    private List<LongShortRatioData> series(double... ratios) {
        List<LongShortRatioData> list = new ArrayList<>();
        for (double r : ratios) {
            LongShortRatioData d = new LongShortRatioData();
            d.setLongShortRatio(String.valueOf(r));
            list.add(d); // time-ascending; last = latest
        }
        return list;
    }

    /** TOP_POSITION rising 1.0->1.2 (+20%), GLOBAL_ACCOUNT falling 2.0->1.6, TAKER 1.5 -> strong signal. */
    private void stubSmartMoney(String symbol) {
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TOP_POSITION"), eq("1h"), anyInt()))
                .thenReturn(Map.of(symbol, series(1.0, 1.05, 1.1, 1.15, 1.18, 1.2,
                        1.2, 1.2, 1.2, 1.2, 1.2, 1.0 == 1.0 ? 1.2 : 1.2, 1.2)));
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("GLOBAL_ACCOUNT"), eq("1h"), anyInt()))
                .thenReturn(Map.of(symbol, series(2.0, 1.95, 1.9, 1.85, 1.8, 1.75,
                        1.72, 1.7, 1.68, 1.66, 1.64, 1.62, 1.6)));
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TAKER"), eq("1h"), anyInt()))
                .thenReturn(Map.of(symbol, series(1.5)));
    }

    @Test
    void analyze_matchesSmartMoneyAccumulation() {
        stubSmartMoney("BTCUSDT");
        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));
        assertEquals("多空比异动", report.getAnalysisType());
        assertEquals(1, report.getMatchedCount());
        AnalysisReport.CoinAnalysis coin = report.getCoins().get(0);
        assertTrue(coin.getScore() >= 60, "smart-money sample should clear minScore");
        assertEquals("long", coin.getSide());
    }

    @Test
    void analyze_skipsWhenTopPositionSeriesTooShort() {
        // only 3 points (< lookback+1=13) -> skipped
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TOP_POSITION"), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series(1.0, 1.1, 1.2)));
        lenient().when(dataFetchService.getLongShortRatioBatch(anyList(), eq("GLOBAL_ACCOUNT"), eq("1h"), anyInt()))
                .thenReturn(Map.of());
        lenient().when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TAKER"), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series(1.5)));
        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));
        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void analyze_skipsWhenTakerMissing() {
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TOP_POSITION"), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series(1.0, 1.05, 1.1, 1.15, 1.18, 1.2,
                        1.2, 1.2, 1.2, 1.2, 1.2, 1.2, 1.2)));
        lenient().when(dataFetchService.getLongShortRatioBatch(anyList(), eq("GLOBAL_ACCOUNT"), eq("1h"), anyInt()))
                .thenReturn(Map.of());
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TAKER"), eq("1h"), anyInt()))
                .thenReturn(Map.of()); // missing
        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));
        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void analyze_flatTopPositionAndWeakTakerBelowMinScore() {
        // TOP_POSITION flat (no rise), TAKER exactly at bullishMin (hit 0), GLOBAL flat -> low score
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TOP_POSITION"), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series(1.0, 1.0, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)));
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("GLOBAL_ACCOUNT"), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series(1.0, 1.0, 1.0, 1.0, 1.0, 1.0,
                        1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)));
        when(dataFetchService.getLongShortRatioBatch(anyList(), eq("TAKER"), eq("1h"), anyInt()))
                .thenReturn(Map.of("BTCUSDT", series(1.0)));
        AnalysisReport report = analyzer.analyze(List.of("BTCUSDT"));
        assertEquals(0, report.getMatchedCount());
    }

    @Test
    void isEnabledReadsConfig() {
        assertTrue(analyzer.isEnabled());          // default true
        assertTrue(analyzer.requiresIntradayData()); // intraday batch
        assertEquals("多空比异动", analyzer.getName());
    }
}
```

> Note: in `stubSmartMoney` the TOP_POSITION list intentionally has 13 points (lookback+1) ending at 1.2, starting at 1.0 → +20% change.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=LongShortRatioAnalyzerTest`
Expected: FAIL — `LongShortRatioAnalyzer` does not exist.

- [ ] **Step 3: Write the analyzer**

```java
package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多空比异动分析器。
 *
 * 信号 = 大户持仓多空比(TOP_POSITION)抬升 + taker主动买盘占优(TAKER>阈值)
 *        + 与散户账户多空比(GLOBAL_ACCOUNT)背离(大户进、散户退)。
 * 默认启用, requiresIntradayData=true, 随 short-term 批次每5分钟运行。
 * LSR 数据缺失时优雅跳过对应 symbol, 不报错。
 */
@Slf4j
@Component
public class LongShortRatioAnalyzer implements Analyzer {

    private final DataFetchService dataFetchService;
    private final AppProperties appProperties;

    public LongShortRatioAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        this.dataFetchService = dataFetchService;
        this.appProperties = appProperties;
    }

    @Override
    public String getName() {
        return "多空比异动";
    }

    @Override
    public String getDescription() {
        var cfg = appProperties.getAnalysis().getLongShortRatio();
        return String.format("大户持仓多空比抬升+taker买盘占优+与散户背离, 评分≥%d (period=%s, lookback=%d)",
                cfg.getMinScore(), cfg.getPeriod(), cfg.getLookback());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getLongShortRatio().isEnabled();
    }

    @Override
    public boolean requiresIntradayData() {
        return true;
    }

    @Override
    public AnalysisReport analyze(List<String> symbols) {
        var cfg = appProperties.getAnalysis().getLongShortRatio();
        String period = cfg.getPeriod();
        int limit = cfg.getLookback() + 1;

        Map<String, List<LongShortRatioData>> topPos =
                dataFetchService.getLongShortRatioBatch(symbols, "TOP_POSITION", period, limit);
        Map<String, List<LongShortRatioData>> global =
                dataFetchService.getLongShortRatioBatch(symbols, "GLOBAL_ACCOUNT", period, limit);
        Map<String, List<LongShortRatioData>> taker =
                dataFetchService.getLongShortRatioBatch(symbols, "TAKER", period, 1);

        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();
        for (String symbol : symbols) {
            List<LongShortRatioData> tp = topPos.get(symbol);
            List<LongShortRatioData> tk = taker.get(symbol);
            if (tp == null || tp.size() < cfg.getLookback() + 1) continue; // 关键序列不足, 跳过
            if (tk == null || tk.isEmpty()) continue;                       // taker 缺失, 跳过

            double agoTP = tp.get(0).getRatioValue();
            double latestTP = tp.get(tp.size() - 1).getRatioValue();
            double tpChangePct = agoTP > 0 ? (latestTP - agoTP) / agoTP * 100 : 0;

            double latestTaker = tk.get(tk.size() - 1).getRatioValue();

            double gaChangePct = 0;
            List<LongShortRatioData> ga = global.get(symbol);
            if (ga != null && ga.size() >= 2) {
                double agoGA = ga.get(0).getRatioValue();
                double latestGA = ga.get(ga.size() - 1).getRatioValue();
                gaChangePct = agoGA > 0 ? (latestGA - agoGA) / agoGA * 100 : 0;
            }

            double tpHit = clamp01(tpChangePct / 10.0);
            double takerHit = clamp01((latestTaker - cfg.getTakerBullishMin()) / 1.0);
            double divHit = (tpChangePct > 0 && gaChangePct < 0)
                    ? clamp01((tpChangePct - gaChangePct) / 20.0) : 0;

            int score = (int) Math.min(100, Math.round(
                    tpHit * cfg.getTopPositionWeight()
                            + takerHit * cfg.getTakerWeight()
                            + divHit * cfg.getDivergenceWeight()));
            if (score < cfg.getMinScore()) continue;

            String side = (tpChangePct > 0 && latestTaker >= cfg.getTakerBullishMin()) ? "long" : "watch";

            Map<String, Object> breakdown = new LinkedHashMap<>();
            breakdown.put("topPosition", (int) Math.round(tpHit * cfg.getTopPositionWeight()));
            breakdown.put("taker", (int) Math.round(takerHit * cfg.getTakerWeight()));
            breakdown.put("divergence", (int) Math.round(divHit * cfg.getDivergenceWeight()));

            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(symbol)
                    .changePercent(tpChangePct)
                    .score(score)
                    .side(side)
                    .scoreBreakdown(breakdown)
                    .detail(String.format(
                            "多空比异动%d分 大户持仓%.2f(%+.1f%%) taker%.2f 散户%+.1f%%",
                            score, latestTP, tpChangePct, latestTaker, gaChangePct))
                    .build());
        }

        matched.sort(Comparator.comparing(
                (AnalysisReport.CoinAnalysis c) -> c.getScore() == null ? 0 : c.getScore(),
                Comparator.reverseOrder()));

        Map<String, Object> used = new LinkedHashMap<>();
        used.put("period", period);
        used.put("lookback", cfg.getLookback());
        used.put("minScore", cfg.getMinScore());
        used.put("source", "config");

        return AnalysisReport.builder()
                .analysisType(getName())
                .analysisTime(LocalDateTime.now())
                .description(getDescription())
                .coins(matched)
                .totalAnalyzed(symbols.size())
                .matchedCount(matched.size())
                .usedParams(used)
                .build();
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1.0, v));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=LongShortRatioAnalyzerTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ba/analyzer/analysis/LongShortRatioAnalyzer.java src/test/java/com/ba/analyzer/analysis/LongShortRatioAnalyzerTest.java
git commit -m "feat: add 多空比异动 LongShortRatioAnalyzer (default enabled)"
```

---

### Task 11: Wire report filename + API param spec

**Files:**
- Modify: `src/main/java/com/ba/analyzer/report/ReportWriter.java:19-29`
- Modify: `src/main/java/com/ba/analyzer/controller/AnalysisController.java` (getParamSpec switch)

- [ ] **Step 1: Add NAME_MAPPING entry**

In `ReportWriter.java`, add `"多空比异动"` to the `Map.of(...)` (this makes 10 entries — `Map.of` supports exactly 10 key/value pairs, so this is the max; if a future entry is added, switch to `Map.ofEntries(...)`):

```java
    private static final Map<String, String> NAME_MAPPING = Map.of(
            "连续上涨", "consecutive_rise",
            "连涨今跌", "rise_then_drop",
            "大幅上涨", "high_rise",
            "连跌今涨", "drop_then_rise",
            "缓涨币", "slow_rise",
            "价涨仓增", "price_drop_oi_rise",
            "低位插针", "low_price_consolidation",
            "短期急涨", "short_term_rise",
            "底部吸筹", "bullish_accumulation",
            "多空比异动", "long_short_ratio"
    );
```

- [ ] **Step 2: Add getParamSpec branch**

In `AnalysisController.java`, in the `getParamSpec` switch, add a case before `default`:

```java
            case "多空比异动" -> {
                params.put("period", Map.of("type", "string", "description", "LSR统计周期", "default", "1h"));
                params.put("lookback", Map.of("type", "int", "description", "与多少根之前比较", "default", 12));
                params.put("minScore", Map.of("type", "int", "description", "最低评分(0-100)", "default", 60));
                params.put("takerBullishMin", Map.of("type", "double", "description", "taker买卖比买盘占优阈值", "default", 1.0));
                yield params;
            }
```

> Note: `getParamSpec` is documentation only. The analyzer's `analyze(symbols)` reads from config; it does not currently consume per-request overrides for these keys (consistent with the spec scope). The param spec documents the config defaults.

- [ ] **Step 3: Verify it compiles and full suite passes**

Run: `mvn test`
Expected: BUILD SUCCESS, all tests pass (existing + new).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/ba/analyzer/report/ReportWriter.java src/main/java/com/ba/analyzer/controller/AnalysisController.java
git commit -m "feat: wire 多空比异动 report filename and param spec"
```

---

## Phase D · Integration verification

### Task 12: Build, run against remote DB, smoke-test endpoints

**Files:** none (manual verification against the live remote DB).

- [ ] **Step 1: Full build**

Run: `mvn package -DskipTests`
Expected: `target/ba-analysis-1.0.0.jar` produced, BUILD SUCCESS.

- [ ] **Step 2: Start the service**

Run: `./start.sh` then `./start.sh log`
Expected: Spring Boot starts on port 8080, no datasource connection errors against `43.155.216.33`. (If connection fails, the remote DB / firewall is the issue, not the code.)

- [ ] **Step 3: Smoke-test the symbol source and new endpoints**

```bash
curl -s "http://localhost:8080/api/analysis/symbols" | head -c 300
curl -s "http://localhost:8080/api/market/lsr/ranking?type=TOP_POSITION&limit=5"
curl -s "http://localhost:8080/api/market/ticker/gainers?limit=5"
curl -s "http://localhost:8080/api/market/premium/funding-ranking?limit=5&order=desc"
curl -s "http://localhost:8080/api/analysis/latest/多空比异动"
```

Expected: non-error JSON. `symbols` returns the trading universe; LSR/ticker/premium rankings return sorted arrays; `latest/多空比异动` returns a report after the first 5-minute batch (or 404 until the first run — then trigger `curl http://localhost:8080/api/analysis/run/多空比异动`).

- [ ] **Step 4: Stop the service**

Run: `./start.sh stop`

- [ ] **Step 5: No commit** (verification only). Record any anomalies for follow-up.

---

## Self-Review notes

- **Spec coverage:** Phase 0 (conn + models + reads) ✓ Tasks 1–5; Unit A (SymbolService) ✓ Task 6; Unit B (MarketController LSR + snapshots) ✓ Tasks 7–8; Unit C (analyzer + config + wiring) ✓ Tasks 9–11; non-goals (no composite-score/MarketFeatureService changes) respected.
- **Deviation:** `getTradingSymbolNames(): List<String>` instead of `getTradingSymbols(): List<SymbolInfo>` (YAGNI — documented above).
- **Type consistency:** `getLongShortRatioBatch(List, String, String, int)` signature identical across `JdbcDataStore`, `DataFetchService`, and analyzer call sites; `getRatioValue()/getPriceChangePercentNum()/getQuoteVolumeNum()/getLastFundingRateNum()/getMarkPriceNum()` getters match their usages in controller/analyzer; `CoinAnalysis` builder fields (`symbol/changePercent/score/side/scoreBreakdown/detail`) match those used by existing analyzers (verified against `CompositeScoreAnalyzer`).
- **AnalysisReport.CoinAnalysis** is assumed to have `score(Integer)`, `side(String)`, `scoreBreakdown(Map)`, `detail(String)`, `changePercent(double)`, `symbol(String)` builder methods — confirmed used by `CompositeScoreAnalyzer`. (`currentPrice` is optional and omitted here since LSR has no price; the report formatter prints `0.0000` for it, acceptable.)
