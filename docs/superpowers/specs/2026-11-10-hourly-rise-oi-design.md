# 小时连涨 + OI 净增扫描（独立页面）设计

- 日期：2026-11-10
- 状态：已批准
- 方案：C（独立服务 + 独立接口 + 独立页面）

## 1. 背景

用户要一个「每小时扫一遍全部币种」的筛选器：找出**最近 4 个小时连续上涨，同时持仓量也在上涨**的币。

现有代码里已经有两块相关能力：

- `价格连续上涨` 分析器：最近 N 根已收盘 K 线每根 `close > open`，支持 `interval=1h`。
- `OI连续上涨` 分析器：最近 N 个小时 OI 逐小时递增，支持 `interval=1h`。

但它们互相独立，没有交集判断；调度只有 `daily`（每天 08:05，1d）和 `short-term`（每 5 分钟，1h）两档，没有「整点跑一次」的档位。用户明确选择**独立页面**，不复用分析器框架。

## 2. 需求定义

| 项 | 定义 |
|---|---|
| 频率 | 每小时一次（整点后执行） |
| 范围 | `symbols` 表中 `status='TRADING'` 的全部合约 |
| 周期 | 固定 `1h` |
| 连续上涨 | 最近 N 根**已收盘** 1h K 线，每根 `close > open`；N 默认 4，可配 2–24 |
| OI 上涨 | **只看区间净增**：窗口结束时的 OI > 窗口开始时的 OI；不要求逐小时递增 |
| OI 字段 | `sum_open_interest`（币数），不用 `sum_open_interest_value`（USDT 名义值） |
| 输出 | 独立页面：命中币种列表 + 关键指标，可点击进入资金流 |

「窗口」指这 N 根 K 线的整体区间：从第 1 根的 `open_time` 到第 N 根的 `close_time`。

## 3. 非目标

- 不做历史留存、回测、胜率统计（结果只在内存保留最近一次快照）。
- 不做推送、告警、定时通知。
- 不修改现有分析器、`ReportWriter`、分析报告页。
- 不向 MySQL 写任何数据（本服务对数据库只读）。

## 4. 方案对比

| 方案 | 做法 | 结论 |
|---|---|---|
| A | 新增联合分析器 + 调度加整点档 | 能复用报告页，但用户要的是独立页面，且分析器框架的「参数 + 报告」形态对单表筛选偏重 |
| B | 挂进现有 5 分钟档 | 每小时会跑 12 次、写 12 份报告，不符合「每小时一次」 |
| **C** | **独立 Service + Controller + 前端页面** | **用户已选**；与现有分析器解耦，展示可定制，代价是多一套取数/展示代码 |

## 5. 详细设计

### 5.1 数据流

```
Scheduler (每小时 :10)
   └─> HourlyRiseOiService.scan(periods, force)
         ├─ SymbolService.getSymbolNames()            // 全量 TRADING 币种
         ├─ DataFetchService.fetchKlines(..., "1h", max(periods+20, 24))
         ├─ DataFetchService.fetchOpenInterestHistoryBatch(..., "1h", periods+20)
         ├─ 过滤未收盘 / 过期 K 线
         ├─ 逐币匹配（连阳 + OI 净增）
         └─ 写入内存快照 latest
                └─> GET /api/market/hourly-rise-oi
                       └─> 前端「小时连涨OI增」页面
```

复用 `DataFetchService` 的 1h K 线缓存（60 秒）和批量查询，不新增数据访问层。

### 5.2 匹配算法

对每个币种：

1. 取 1h K 线，丢弃 `closeTime > now` 的未收盘根；若最后一根距 `now` 超过 2 小时，视为数据过期，跳过。
2. K 线数量 `< periods` → 跳过（新币/停牌）。
3. 最近 `periods` 根必须全部 `close > open`；任一不满足 → 不命中。
4. 计算窗口：
   - `windowStart = 第 1 根的 openTime`
   - `windowEnd = 第 N 根的 closeTime`
5. 在 OI 序列中取两个端点：
   - `oiStart`：时间戳等于 `windowStart` 的记录；找不到时取 ±30 分钟内最近的记录。
   - `oiEnd`：优先取时间戳等于 `windowEnd` 的记录（±30 分钟）；**找不到时退化为窗口内最新可用点**（`≤ now` 且 `≥ windowStart + 2h`）。
   - 任一端点缺失、或 `oiEnd` 距 `windowEnd` 超过 2 小时 → 跳过，并计入 `skippedNoOi`。
6. `oiStart <= 0` → 跳过。
7. `oiEnd > oiStart` 才算命中（净增，允许中间回落）。
8. 指标：
   - `priceChangePercent = last.close / first.open - 1`
   - `oiChangePercent = oiEnd / oiStart - 1`
   - `oiStartTime` / `oiEndTime` / `oiSpanHours`：实际使用的 OI 区间（退化时可能不足 4 小时，页面如实展示）
   - `quoteVolume24h = 最近 24 根已收盘 1h K 线的 quoteAssetVolume 之和`（不足 24 根则按实际根数求和）

命中后按 `oiChangePercent` 降序、`priceChangePercent` 降序排序。

### 5.3 服务与缓存

`HourlyRiseOiService`：

- 依赖：`DataFetchService`、`SymbolService`。
- 缓存结构：`ConcurrentHashMap<Integer, ScanResult>`，**按 `periods` 分槽**。
  - 调度器扫描配置的 `periods`（默认 4）并写入对应槽位。
  - 页面选 5 小时时首次触发一次扫描，结果写入 `5` 槽；之后轮询只读该槽，不会因调度器写入 `4` 槽而被顶掉。
- `ScanResult scan(int periods)`：扫描并写缓存。
- `ScanResult getLatest(int periods, boolean refresh)`：
  - `refresh=false` 且该 `periods` 有缓存 → 直接返回。
  - 否则触发扫描；用 `ReentrantLock.tryLock(10s)` 防止并发，超时则返回该槽缓存，仍为空则抛 `503`（前端提示「扫描进行中，请稍后」）。
- 单次扫描预计 1–3 秒（520 个币，两条批量查询）。

结果模型（`record`，直接序列化为 JSON）：

```java
record Match(String symbol, double price, double priceChangePercent,
             double oiChangePercent, int periods, long windowStart,
             long windowEnd, long oiStartTime, long oiEndTime,
             double quoteVolume24h) {}

record ScanResult(long scannedAt, int totalSymbols, int matchedCount,
                  int periods, int skippedNoOi, List<Match> matches) {}
```

### 5.4 定时任务

`HourlyRiseOiScheduler`：

```java
@Scheduled(cron = "${binance.schedule.hourly-scan}")
public void runHourlyScan() { ... }
```

- 默认 cron：`0 10 * * * ?`（整点后 10 分钟，等 ba-sync 的 `mid-freq-sync`（每 30 分钟）把 1h OI 写入库）。
- 受 `binance.hourly-scan.enabled` 控制，默认 `true`。
- 异常捕获后记录 `log.error`，保留上一次快照，不影响服务。

### 5.5 API

`HourlyRiseOiController`：

```
GET /api/market/hourly-rise-oi?periods=4&refresh=false
```

- `periods`：默认 4，clamp 到 2–24。
- `refresh=true`：强制重扫（同步等待）；`false`：返回缓存，没有缓存时触发一次扫描。
- 返回 `ScanResult`；扫描失败返回 500 + 错误信息。
- 前端错误提示复用现有 `error-msg` 样式。

### 5.6 前端

- `MARKET_ITEMS` 增加 `{ view: 'market-hourly-rise', label: '小时连涨OI增' }`。
- `renderCurrentView` 增加对应 `case`。
- `renderHourlyRise()`：
  - 顶部说明：口径、扫描时间、下次扫描时间。
  - 控件：周期选择（4/5/6 小时）、「立即扫描」按钮。
  - 表格列：币种、现价、窗口涨幅、OI 变化、连涨小时数、24h 成交额、窗口结束（北京时间）。
  - 行点击 → 跳转资金流页面（复用现有 `state.flowSymbol` 逻辑）。
  - 空态：`没有符合条件的币种`。
  - 自动刷新：每 60 秒读一次缓存（`refresh=false`），不触发重扫。
- 样式复用 `result-table`、`alert-tag`、`btn-primary`、`result-summary`；如缺「正值/负值」配色则补 `style.css`。

### 5.7 配置

`AppProperties` 新增：

```yaml
binance:
  hourly-scan:
    enabled: true
    periods: 4
  schedule:
    hourly-scan: "0 10 * * * ?"
```

同步写入三处：`src/main/resources/application.yml`、`config/application.yml.example`、本地 `config/application.yml`（本地文件不入库）。

### 5.8 日志

每次扫描记录一条：

```
hourly-rise-oi scan: symbols=520 matched=7 skippedNoOi=3 periods=4 elapsed=1.8s
```

调度器额外记录开始/结束；异常记录完整堆栈。

### 5.9 OI 新鲜度实测与对策

2026-11-10 10:54 实测（522 个币）：

| 指标 | 结果 |
|---|---|
| 1h OI 最新点距现在 | 55–115 分钟，平均 94 分钟 |
| 最新点在 1 小时内 | 180 / 522 |
| 最新点在 2 小时内 | 522 / 522 |
| 每币近 24 小时 OI 点数 | 23–24 个（小时级） |

原因：ba-sync 的 `syncOiHistoryIfStale` 在「最新 OI 距 now < 3 小时」时整轮跳过，所以 1h OI 实际是每 2–3 小时才补一次。

对策：

1. **ba-analysis 侧（必做）**：`oiEnd` 允许退化为窗口内最新可用点（见 5.2 第 5 步），并在结果里返回实际 OI 区间，避免「OI 同步慢」被误判成「没有符合条件的币」。
2. **ba-sync 侧（已实现）**：1h OI 的新鲜度阈值已从 `periodMs * 3` 收紧到 `periodMs`，cron 改为 `:05/:35`，
   每个整点在 2 分半发布后 5 分钟内入库；per-symbol 判鲜 + 增量区间拉取也已上线。

若不改 ba-sync，功能仍可用，但 OI 区间可能是 2–4 小时，页面会显示实际区间，需要人工留意。

## 6. 边界与失败处理



| 情况 | 处理 |
|---|---|
| OI 缺失或过期 | 跳过该币，计入 `skippedNoOi`，不报错 |
| K 线不足 `periods` | 跳过 |
| 未收盘 K 线 | 丢弃后再判断 |
| 数据源/DB 异常 | 调度器捕获并记录；接口返回 500；页面显示上次结果或错误 |
| 并发扫描 | `tryLock`，拿不到锁返回缓存 |
| 服务重启 | 缓存清空，首次访问触发一次扫描 |
| 时区 | 统一北京时间展示（现有 `dateOf` 口径） |

## 7. 测试计划

单元测试（Mockito mock `DataFetchService` / `SymbolService`）：

1. 4 连阳 + OI 净增 → 命中，涨幅/OI 变化计算正确。
2. 4 连阳但 OI 净减 → 不命中。
3. 中间一根阴线 → 不命中。
4. 未收盘 K 线参与时会误判 → 验证被丢弃。
5. OI 端点缺失/过期 → 计入 `skippedNoOi`。
6. 不足 `periods` 根 K 线 → 跳过。
7. `force=false` 命中缓存；`force=true` 重扫。
8. 并发 `refresh` 只执行一次扫描。

控制器测试：`periods` clamp、`refresh` 透传、异常返回 500。

其他验证：

- `mvn test` 全绿。
- `node --check src/main/resources/static/js/app.js`。
- 本地起服务后调用接口，抽 1–2 个币用 SQL 手工核对窗口涨幅与 OI 变化。

## 8. 影响文件

新增：

- `src/main/java/com/ba/analyzer/service/HourlyRiseOiService.java`
- `src/main/java/com/ba/analyzer/scheduler/HourlyRiseOiScheduler.java`
- `src/main/java/com/ba/analyzer/controller/HourlyRiseOiController.java`
- `src/test/java/com/ba/analyzer/service/HourlyRiseOiServiceTest.java`
- `src/test/java/com/ba/analyzer/controller/HourlyRiseOiControllerTest.java`

修改：

- `src/main/java/com/ba/analyzer/config/AppProperties.java`（新增 `HourlyScanConfig`）
- `src/main/resources/application.yml`、`config/application.yml.example`、本地 `config/application.yml`
- `src/main/resources/static/js/app.js`（导航、路由、页面）
- `src/main/resources/static/css/style.css`（如需）
- `README.md`、`CLAUDE.md`（接口/页面说明）

不改：

- 现有分析器、`ReportWriter`、`AnalysisScheduler`、分析报告页。

## 9. 我的默认决定（可推翻）

1. 周期固定 `1h`，页面只暴露 `periods`（默认 4）。
2. 不做流动性硬过滤（需求是「每一个币种」），但返回 24h 成交额供人工判断。
3. `hourly-scan.enabled` 默认 `true`（用户明确要它每小时跑）。
4. 扫描时间整点后 10 分钟，给 OI 同步留时间。
5. 结果只存内存，不落库、不做历史。
6. OI 端点：优先窗口结束整点（±30 分钟）；缺失时退化为窗口内最新可用点，并返回实际 OI 区间。
7. 缓存按 `periods` 分槽；页面自动刷新只读缓存，不触发重扫。
8. 排序按 OI 增幅降序。
9. 并发扫描用 `tryLock(10s)`；拿不到锁时返回该 `periods` 的缓存，仍为空则 503。

## 10. 待确认

1. ~~**是否同时改 ba-sync**~~：已决定并已实现（1h OI 门控收紧到 `periodMs`，cron `:05/:35`），OI 区间与 4 小时价格窗口可严格对齐。

