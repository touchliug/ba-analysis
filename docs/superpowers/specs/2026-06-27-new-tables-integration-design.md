# 设计:接入远端库新表(多空比 / 快照接口 / symbols 数据源)

> 日期:2026-06-27
> 状态:已通过 brainstorming,待评审 → 实现计划

## 背景

数据库切到远端公网库 `43.155.216.33:3306/ba`(库名/账号/密码不变)。新库由 `ba-sync` 维护,表结构见 `doc/数据库表结构说明.md`,共 7 张表。

现状对照:
- 现有读取的 `klines` / `open_interest` / `funding_rate` 三张表,列名与新文档**逐字一致**,无需改动即可读远端库。
- 新库多出 4 张表代码完全没用:`symbols`(合约元信息快照)、`ticker_24h`(24h 行情快照)、`premium_index`(标记价/资金费快照)、`long_short_ratio`(多空比,历史累积,4 种 ratio_type)。

## 目标(本次范围)

1. **地基**:切连接 + 为新表加 model 和只读访问层。
2. **换数据源**:`SymbolService` 从「klines 去重」改为读 `symbols` 表,带兜底。
3. **查询接口**:新建 `MarketController`,对多空比 + 两张快照表(ticker_24h / premium_index)提供只读查询/排名接口。
4. **新分析器**:「多空比异动」`LongShortRatioAnalyzer`,默认启用,随 5 分钟批次运行。

## 非目标(明确排除)

- ❌ 多空比**不**并入 `CompositeScoreAnalyzer` 打分(`social-weight` 维持为 0 占位)。
- ❌ 不改 `MarketFeatureService`、不改综合评分逻辑。
- ❌ 不动现有 klines/OI/funding 读取路径。
- ❌ 不新增定时任务(查询接口是被动只读;新分析器复用现有 short-term 批次)。

## 架构原则

沿用现有三层只读架构,不引入新访问范式:

```
MySQL(远端) → JdbcDataStore(原始 SQL,只读) → DataFetchService(封装 + 落后告警)
                                                  ├→ Analyzer(LongShortRatioAnalyzer)
                                                  └→ MarketController(查询接口)
SymbolService 直接持 JdbcTemplate(与现状一致)
```

新 model 沿用现有风格:价格/比率字段存 `String`,提供 `getXxxValue()` 解析成 `double`(`NumberFormatException` 兜底返回 0),参照 `OpenInterestData`。

---

## Phase 0 · 地基

### 0.1 连接

`src/main/resources/application.yml` `spring.datasource.url` 的 host:
`192.168.193.128` → `43.155.216.33`。其余参数(库名 `ba`、`useSSL=false`、`allowPublicKeyRetrieval=true`、`serverTimezone=Asia/Shanghai`、账号 `root`、密码)不变。

### 0.2 新 model(`com.ba.analyzer.model`)

**`LongShortRatioData`**(对应 `long_short_ratio` 表)
| 字段 | 类型 | 说明 |
|---|---|---|
| symbol | String | |
| ratioType | String | GLOBAL_ACCOUNT / TOP_ACCOUNT / TOP_POSITION / TAKER |
| period | String | 如 `1h` |
| timestamp | long | UTC ms |
| longShortRatio | String | 比率值 |
| longValue | String | 多头侧值 |
| shortValue | String | 空头侧值 |

getter:`getRatioValue()` / `getLongValueNum()` / `getShortValueNum()` 解析 double。

**`Ticker24hData`**(对应 `ticker_24h` 表):symbol, priceChangePercent, lastPrice, openPrice, highPrice, lowPrice, weightedAvgPrice, volume, quoteVolume, tradeCount(long), openTime(long), closeTime(long), capturedAt(long)。getter:`getPriceChangePercentNum()` / `getLastPriceNum()` / `getQuoteVolumeNum()`。

**`PremiumIndexData`**(对应 `premium_index` 表):symbol, markPrice, indexPrice, estimatedSettlePrice, lastFundingRate, nextFundingTime(long), interestRate, time(long)。getter:`getMarkPriceNum()` / `getLastFundingRateNum()`。

> `symbols` 表复用现有 `SymbolInfo` model(字段已齐),按需在 `JdbcDataStore` 里映射。

### 0.3 `JdbcDataStore` 新增只读方法

- `List<LongShortRatioData> getLongShortRatio(String symbol, String ratioType, String period, int limit)`
  SQL:`SELECT * FROM long_short_ratio WHERE symbol=? AND ratio_type=? AND period=? ORDER BY timestamp DESC LIMIT ?`,结果 reverse 成时间升序(与 `getKlines` 一致)。
- `List<LongShortRatioData> getLatestLongShortRatioAll(String ratioType, String period)`
  每个 symbol 取最新一条(用 `timestamp` 最大行)。实现:子查询取每 symbol 最大 timestamp,或简单按 symbol 分组取最新。供 ranking 接口用。
- `List<Ticker24hData> getAllTicker24h()` / `Ticker24hData getTicker24h(String symbol)`。
- `List<PremiumIndexData> getAllPremiumIndex()` / `PremiumIndexData getPremiumIndex(String symbol)`。
- `List<SymbolInfo> getTradingSymbols()`:`SELECT * FROM symbols WHERE status='TRADING' AND quote_asset='USDT' ORDER BY symbol`。

行映射沿用 private RowMapper 风格。`interval` 等保留字加反引号(现有代码已遵守;`timestamp` 同样反引号)。

### 0.4 `DataFetchService` 封装

- `getLongShortRatio(symbol, ratioType, period, limit)` → 透传。
- `getLongShortRatioBatch(symbols, ratioType, period, limit)` → 逐 symbol,缺失不放入 map,< 90% 时打 warn(与 OI/kline 一致)。
- ticker/premium 的查询走 controller 直接调 `JdbcDataStore` 即可(快照全市场单次查询,无需 batch 封装),保持 `DataFetchService` 聚焦分析路径。

---

## 单元 A · `SymbolService` 换数据源

`getSymbolNames()` 改为:
1. 先 `jdbcDataStore.getTradingSymbols()` 取 `symbols` 表中 `status='TRADING' AND quote_asset='USDT'` 的 symbol 列表。
2. **兜底**:若结果为空,回退到原 `SELECT DISTINCT symbol FROM klines WHERE interval='1d'`,并打 warn(`symbols table empty, falling back to klines`)。
3. 两者都空 → 返回空 + 原有 warn。

> 注:文档提示 `symbols.status` 下架后会残留旧 `TRADING`,klines 去重同样残留;两种口径都接受残留,不在本次解决。改读 `symbols` 的收益是拿到规范的合约全集(含未来要用的精度/上线时间等元信息),且不依赖 klines 是否已灌入。

`SymbolService` 注入改为持有 `JdbcDataStore`(而非裸 `JdbcTemplate`),fallback 查询移到 `JdbcDataStore` 里一个 `getSymbolNamesFromKlines()` 方法,保持 SQL 收口在数据访问层。

---

## 单元 B · `MarketController` 查询接口

新建 `com.ba.analyzer.controller.MarketController`,`@RequestMapping("/api/market")`,只读、无副作用、无速率限制。

**多空比**
- `GET /lsr/{symbol}?period=1h&limit=48` — 返回该 symbol 4 种 ratio_type 各最近 N 点,结构 `Map<ratioType, List<LongShortRatioData>>`。
- `GET /lsr/ranking?type=TOP_POSITION&period=1h&limit=50` — 全市场按该类多空比**最新值**降序前 N。`type` 校验在 4 种枚举内,非法返回 400。

**24h 行情快照**
- `GET /ticker/{symbol}` — 单 symbol,无则 404。
- `GET /ticker/gainers?limit=50` — 按 `price_change_percent` 降序。
- `GET /ticker/losers?limit=50` — 升序。
- `GET /ticker/volume?limit=50` — 按 `quote_volume` 降序。

**标记价/资金费快照**
- `GET /premium/{symbol}` — 单 symbol,无则 404。
- `GET /premium/funding-ranking?limit=50&order=desc` — 按 `last_funding_rate` 排序(desc=最高正费率,asc=最低/最负),前 N。

排序/截断在 Java 侧做(快照表全市场行数 ~数百,单次全量读再排序成本可接受;ranking 用 model 的数值 getter 排序)。`limit` 默认 50,做上限保护(如 ≤500)。

---

## 单元 C · `LongShortRatioAnalyzer`「多空比异动」

`com.ba.analyzer.analysis.LongShortRatioAnalyzer`,`@Component`,**直接实现 `Analyzer`**(像 `CompositeScoreAnalyzer`,不走 `AbstractKlineAnalyzer` 的日线预加载)。

- `getName()` = `"多空比异动"`,`requiresIntradayData()` = `true`,`isEnabled()` 读 config(**默认 `true`**)。
- `requiredDays()` 不涉及(intraday)。

**数据**:对每个 symbol,经 `DataFetchService.getLongShortRatioBatch` 取 4 类 ratio_type 近 `lookback` 点(period=`1h`)。

**信号与评分(0–100)**:
- `TOP_POSITION`(大户持仓多空比):最新值 vs `lookback` 前的变化 → 抬升给分(大户加多)。
- `TAKER`(主动买卖量比):最新值 > 1 给分,越高越高分(主动买盘占优)。
- 背离:`TOP_POSITION` 上行 同时 `GLOBAL_ACCOUNT`(散户账户多空比)下行/低位 → 背离加成(大户进、散户退)。
- 各子项 0–1 命中度 × 权重,汇总封顶 100;低于 `minScore` 不输出。
- 任一关键 ratio_type 数据缺失 → 该 symbol 跳过(优雅返回空,不报错)。默认启用且每 5 分钟跑,必须对缺数据健壮。

**输出**:`AnalysisReport.CoinAnalysis`(symbol、score、detail 文案含各 ratio 最新值与变化、side 由信号定多/观望),按 score 降序。

**配置** `AppProperties.LongShortRatioConfig`:
```
enabled=true, period="1h", lookback=12, minScore=60,
topPositionRiseMin(抬升阈值%), takerBullishMin(默认1.0),
divergenceBonus(背离加成), 各维度 weight
```
yml 加 `binance.analysis.long-short-ratio.*` 对应块。

**接线**:
- `ReportWriter.NAME_MAPPING` 加 `"多空比异动" → "long_short_ratio"`(注:`NAME_MAPPING` 现用 `Map.of`,9 个条目;加第 10 个仍在 `Map.of` 10 对上限内,OK)。
- `AnalysisController.getParamSpec` 加 `"多空比异动"` 分支(period/lookback/minScore/各阈值)。
- `AnalysisConfig` 加 `longShortRatio` 字段。

---

## 测试

- **`JdbcDataStore`**:新方法用 H2 或 mock JdbcTemplate 验证 SQL 列映射(参照现有测试组织)。重点:`long_short_ratio` 列名、reverse 顺序、ranking 取最新。
- **`SymbolService`**:symbols 表有数据走 symbols;空则 fallback klines;都空返回空。
- **`LongShortRatioAnalyzer`**:沿用现有 `*AnalyzerTest` 风格,造 4 类 ratio 序列,断言:典型跟庄样本命中、缺 TAKER 数据时跳过、低于 minScore 不输出、背离加成生效。
- **`MarketController`**:`@WebMvcTest` 验证排序/截断/404/非法 type 400。
- 现有测试不受影响(未改动 klines/OI/funding 路径与综合评分)。

## 风险

- 远端公网库延迟/连接稳定性高于内网;HikariCP 池大小维持 10,不在本次调。
- `symbols` 表残留下架币 → 与现状(klines 残留)同量级,可接受。
- 快照接口全量读排序:行数小,可接受;若日后行数膨胀再下推到 SQL `ORDER BY ... LIMIT`。

## 交付顺序

Phase 0 → 单元 A → 单元 B → 单元 C。每步独立可编译可跑;A 完成即换源生效,B 完成即有查询接口,C 完成即新分析器上线。
