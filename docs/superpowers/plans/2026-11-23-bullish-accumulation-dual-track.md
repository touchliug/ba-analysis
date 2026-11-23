# 「底部吸筹」双轨蓄力观察池 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `BullishAccumulationAnalyzer`（「底部吸筹」）重构为双轨蓄力观察池（`[二波缩量坑]` + `[首波OI潜伏]` / `[首波试盘潜伏]`），找出还在坑里极度缩量或暗中增仓、尚未暴涨启动的币，并在结果详情中输出盯盘点火线。

**Architecture:** 复用 `AbstractKlineAnalyzer` 的已收盘日线截取框架，在 `BullishAccumulationAnalyzer` 中加载 `lookbackDays + 2` 根日 K 线并批量读取最近 6 个日线币本位 OI 点（`dataFetchService.fetchOpenInterestHistoryBatch(symbols, 6)`）。每个币先过流动性与「未破位、未启动」通用门槛，再分别计算轨道 1（二波缩量坑）与轨道 2（首波 OI/试盘潜伏）得分，取最高轨进入结果并输出点火触发价与成交额。

**Tech Stack:** Java 17, Spring Boot 3.2, JUnit 5, Mockito, Maven

**Spec:** `docs/superpowers/specs/2026-11-23-bullish-accumulation-dual-track-design.md`

## Global Constraints

- 只读 MySQL，不写数据库、不调币安 API、不改 `ba-sync`。
- 分析器名称保持 `"底部吸筹"`，前端与 `ReportWriter` 无需改动。
- 持仓量使用币本位 `OpenInterestData.getOpenInterest()`（对应 `sum_open_interest`），不用 USDT 名义值。
- 新增配置项使用 `lookback-days`、`wave-drawdown-min`、`wave-drawdown-max` 等新键名，且打分采用 30 分基础分（满分 100，默认 `min-score: 65`），保证即使外部旧配置残留 `min-score: 60` 也能过滤掉 50~58 分的未企稳噪音。

## Review Focus

1. **正在加速下跌的币（3 日跌幅 < -6% 或当日跌幅 < -5%）**：即使满足前波主升与缩量（如 EVAA 在 2026-11-08~11-10），也必须被 Gate 3 拦下，不能当作已企稳蓄力输出。
2. **当日已经暴涨启动的币（当日涨幅 ≥ `maxDailyRisePercent`，默认 8%）**：必须被 Gate 3 拦下（如 EVAA 在 2026-11-14 当日 +31%），不再留在潜伏观察池。
3. **OI 历史缺失或点数不足 6 个（如新币或 `fetchOpenInterestHistoryBatch` 返回 null/空列表）**：不得抛 NPE；轨道 1 和轨道 2 的试盘脚印子轨仍正常打分，仅 OI 加成为 0。
4. **前波高点发生在最近 4 天内（`e - swingHighIdx < 5`）**：刚见顶刚开始跌的币不算「回调进坑蓄力」，轨道 1 必须跳过。
5. **两条轨道同时满足时的标签与得分**：取分数更高的轨道为主标签；轨道 1 同时有 5 日 OI 净增 ≥10%（且 ≥3 天递增）时叠加 5 分共振分（总分封顶 100）。

---

### Task 1: 更新 `BullishAccumulationConfig` 与配置文件

**Files:**
- Modify: `src/main/java/com/ba/analyzer/config/AppProperties.java:92-108`
- Modify: `src/main/resources/application.yml:67-76`
- Modify: `config/application.yml.example:79-88`

**Interfaces:**
- Produces: `AppProperties.BullishAccumulationConfig` with Lombok `@Data` getters/setters:
  - `boolean enabled = false`
  - `int lookbackDays = 60`
  - `int minScore = 65`
  - `double minWaveRisePercent = 40.0`
  - `double waveDrawdownMin = 25.0`
  - `double waveDrawdownMax = 55.0`
  - `double maxDryVolumeRatio = 0.15`
  - `double lowPricePercentile = 45.0`
  - `double minOiRisePercent = 15.0`
  - `double maxDailyRisePercent = 8.0`
  - `double minLiquidityUsdt = 2_000_000.0`

- [ ] **Step 1: 修改 `AppProperties.BullishAccumulationConfig` 字段与默认值**

在 `src/main/java/com/ba/analyzer/config/AppProperties.java` 中将 `BullishAccumulationConfig` 更新为上述 11 个字段及默认值。

- [ ] **Step 2: 同步更新 `src/main/resources/application.yml` 与 `config/application.yml.example`**

将两处 `bullish-accumulation:` 配置块更新为：
```yaml
    bullish-accumulation:
      enabled: false
      lookback-days: 60
      min-score: 65
      min-wave-rise-percent: 40.0
      wave-drawdown-min: 25.0
      wave-drawdown-max: 55.0
      max-dry-volume-ratio: 0.15
      low-price-percentile: 45.0
      min-oi-rise-percent: 15.0
      max-daily-rise-percent: 8.0
      min-liquidity-usdt: 2000000
```
（若本地存在未跟踪的 `config/application.yml`，一并同步该段落以保持本地运行一致。）

---

### Task 2: 重构 `BullishAccumulationAnalyzer` 及单元测试（TDD）

**Files:**
- Modify: `src/main/java/com/ba/analyzer/analysis/BullishAccumulationAnalyzer.java`
- Modify: `src/test/java/com/ba/analyzer/analysis/BullishAccumulationAnalyzerTest.java`

**Interfaces:**
- Consumes: `AppProperties.BullishAccumulationConfig`, `DataFetchService.fetchDailyKlines(List<String>, int)`, `DataFetchService.fetchOpenInterestHistoryBatch(List<String>, int)`
- Produces:
  - `BullishAccumulationAnalyzer.doAnalyze(Map<String, List<KlineData>> klineMap, Map<String, Object> params)`
  - `BullishAccumulationAnalyzer.fetchData(List<String> symbols, Map<String, Object> params)` 请求 `lookbackDays + 2` 根日 K 线

- [ ] **Step 1: 在 `BullishAccumulationAnalyzerTest` 编写覆盖双轨与 5 个边界条件的失败测试**

替换 `src/test/java/com/ba/analyzer/analysis/BullishAccumulationAnalyzerTest.java` 中的旧 4 因子测试，编写以下 6 个测试方法：
1. `catchesSecondWaveDryPullbackPit_likeEvaaOn1113()`：构造 60 根日线（前段含 5 日涨 +150% 且放量 4×，高点在 12 天前，当前距高点回撤 37%，近 5 日缩量至峰值 3.7%，最后 2 日温和收阳 +1%/+0.7%），断言命中 1 条、`score == 95`、`detail` 含 `"[二波缩量坑]"` 与 `"盯盘点火"`。
2. `rejectsCoinStillFallingHardIn3Days()`：与测试 1 相同的二波缩量结构，但近 3 日累计跌幅为 `-12%`（模拟 EVAA 11-08~11-10），断言返回空列表。
3. `rejectsCoinAlreadyBrokenOutToday()`：与测试 1 相同，但最后一日涨幅为 `+12%`（超过 `maxDailyRisePercent = 8.0`），断言返回空列表。
4. `rejectsWhenSwingHighIsTooRecent()`：前波高点发生在 2 天前（`e - swingHighIdx < 5`），断言轨道 1 不命中。
5. `catchesFirstWaveOiAccumulationInLowRange()`：60 天无大幅主升，当前价格位于 60 日 20% 低分位、10 日涨跌幅 +3%，模拟 `fetchOpenInterestHistoryBatch` 返回 6 个日线币本位 OI 点（5 日净增 +30%，4 天递增），当日小阳线，断言命中 1 条、`score == 85`、`detail` 含 `"[首波OI潜伏]"`。
6. `catchesFirstWaveFootprintWhenOiMissing()`：`fetchOpenInterestHistoryBatch` 返回空 Map，60 日低分位横盘，第 `e-6` 日出现 1 根成交量 4× 20 日均量、振幅 12%、涨幅 +3% 的试盘针，最近 3 日均量缩至 20 日均量的 0.4× 且当日收阳，断言命中 1 条、`score == 75`、`detail` 含 `"[首波试盘潜伏]"`。

- [ ] **Step 2: 运行测试确认处于 RED（编译或断言失败）**

Run: `mvn -o test -Dtest=BullishAccumulationAnalyzerTest -DfailIfNoTests=false`
Expected: FAIL（`BullishAccumulationAnalyzer` 仍引用旧参数与旧逻辑）

- [ ] **Step 3: 实现 `BullishAccumulationAnalyzer` 的双轨蓄力与点火线计算**

按 Spec 第 4、5 节实现 `BullishAccumulationAnalyzer.java`：
- `getDefaultParams()`、`resolveParams()`、`getParamSpec()` 绑定 10 个运行参数：`lookbackDays`、`minScore`、`minWaveRisePercent`、`waveDrawdownMin`、`waveDrawdownMax`、`maxDryVolumeRatio`、`lowPricePercentile`、`minOiRisePercent`、`maxDailyRisePercent`、`minLiquidityUsdt`。
- `fetchData()` 调用 `dataFetchService.fetchDailyKlines(symbols, (int) params.get("lookbackDays") + 2)`。
- `doAnalyze()` 批量调用 `dataFetchService.fetchOpenInterestHistoryBatch(symbols, 6)`，按 Spec 4.1–4.3 执行 Gate 1–3、轨道 1、轨道 2 打分与点火线格式化。

- [ ] **Step 4: 运行单元测试与全量测试确认全部 GREEN**

Run: `mvn -o test`
Expected: `Tests run: 133, Failures: 0, Errors: 0, Skipped: 0` 且 `BUILD SUCCESS`

- [ ] **Step 5: 提交并推送代码**

```powershell
git -C D:\ccdeskwork\ba-analysis add docs/superpowers/specs/2026-11-23-bullish-accumulation-dual-track-design.md docs/superpowers/plans/2026-11-23-bullish-accumulation-dual-track.md src/main/java/com/ba/analyzer/config/AppProperties.java src/main/resources/application.yml config/application.yml.example src/main/java/com/ba/analyzer/analysis/BullishAccumulationAnalyzer.java src/test/java/com/ba/analyzer/analysis/BullishAccumulationAnalyzerTest.java
git -C D:\ccdeskwork\ba-analysis commit -m "feat(analysis): rebuild bullish accumulation as dual-track pit watchlist"
git -C D:\ccdeskwork\ba-analysis -c http.proxy=http://127.0.0.1:7890 -c https.proxy=http://127.0.0.1:7890 push
```
