# 连续上涨 / 连续下跌 / OI 连涨扫描设计

> 日期：2026-11-06
> 状态：待确认

## 目标

新增 3 个分析器：

1. **价格连续上涨**：连续 N 根已收盘 K 线收阳（`close > open`）的币。
2. **价格连续下跌**：连续 N 根已收盘 K 线收阴（`close < open`）的币。
3. **OI 连续上涨**：连续 N 个已收盘周期持仓量（`sum_open_interest`）递增的币。

N 默认 5，可配置；周期默认 `1d`，可选 `1d` / `1h`。

## 非目标

- 不修改现有“连续上涨”分析器（它仍是首阴日候选池）。
- 不新增 4h / 5m 周期；`ba-sync` 当前稳定同步 1d / 1h K 线与 OI。
- 不新增数据库表或写入逻辑。

## 行为定义

- 只使用**已收盘**数据：末端未收盘 K 线丢弃。
- 价格连涨/连跌按每根 K 线的 `close > open` / `close < open` 判断，不要求涨幅阈值。
- OI 连涨按相邻 `sum_open_interest` 严格递增判断。
- 数据新鲜度：
  - `1d` K 线：最新已收盘根不得超过 1 天；
  - `1d` OI：最新点不得超过 2 天（`ba-sync` 的日 OI 同步在 08:10 运行，daily-analysis 在 08:05 运行，分析时最新日 OI 通常是前一天的点）；
  - `1h`：最新已收盘 K 线/OI 点不得超过 2 小时（容忍同步延迟）。
- 输出实际连续根数、区间累计涨跌幅、当前价/当前 OI、周期。
- 数据缺失或不足 N 根时跳过该 symbol，不报错。

## 实现方式

- 新增 `AbstractConsecutiveAnalyzer`，复用一个 `interval` + `periods` 参数解析、批量取数和已收盘过滤逻辑。
- 三个 `@Component` 分析器：
  - `价格连续上涨` → `PriceRiseStreakAnalyzer`
  - `价格连续下跌` → `PriceFallStreakAnalyzer`
  - `OI连续上涨` → `OiRiseStreakAnalyzer`
- `DataFetchService` 新增按周期取 K 线（1d/1h 各自缓存）和按周期取 OI 的方法。
- `Analyzer.param` 增加 `select` 类型；前端参数表单对 `select` 渲染下拉框，让“天/小时”可直接选择。
- 配置新增 `binance.analysis.price-rise-streak`、`price-fall-streak`、`oi-rise-streak`，默认 `enabled: false`、`interval: 1d`、`periods: 5`。
- 调度：`requiresIntradayData()` 跟随配置的 `interval`；`1h` 进入每 5 分钟 short-term 批次，`1d` 进入每日 daily 批次。页面手动触发不受批次限制。
- `ReportWriter.NAME_MAPPING` 增加 3 个文件名映射。

## 风险

- 1h OI 由 `ba-sync` 约 30 分钟更新一次，可能出现短时滞后；用 2 小时新鲜度阈值容忍。
- 1d OI 只回填最近约 30 天，N 很大时可能数据不足，按“跳过”处理。
- 现有“连续上涨”与新“价格连续上涨”同时存在，名字相近但语义不同；新分析器名称使用完整“价格连续上涨/下跌”以区分。

## 测试

- 价格连涨/连跌：N 根收阳/收阴命中；中间一根反向不命中；未收盘 K 线不计入。
- OI 连涨：N 次严格递增命中；持平/下降不命中；数据不足跳过。
- `DataFetchService`：1d/1h 缓存互不干扰；OI 按周期读取。
- 参数：默认值来自配置；`interval` 只接受 `1d/1h`。
