# 连续上涨 / 连续下跌 / OI 连涨实现计划

> 设计：`docs/superpowers/specs/2026-11-06-consecutive-scan-design.md`

**目标�?* 新增 3 个可选周期（1d/1h）和连续根数（默�?5）的分析器�?
## 任务 1：数据层支持 1d/1h K 线和 OI

- Modify `DataFetchService`：`fetchKlines(symbols, interval, limit)`，按 interval 分缓存；`fetchOpenInterestHistoryBatch(symbols, period, limit)`；`fetchDailyKlines` 保持兼容�?- Test `DataFetchServiceTest`�?d �?1h 缓存互不干扰；OI �?period 读取�?
## 任务 2：连续分析器基类 + 三个分析�?
- Create `AbstractStreakAnalyzer`：interval/periods 参数、已收盘过滤、报告构建、周期新鲜度�?- Create `PriceRiseStreakAnalyzer`（价格连续上涨）、`PriceFallStreakAnalyzer`（价格连续下跌）、`OiRiseStreakAnalyzer`（OI连续上涨）�?- Test�? 个分析器各自命中/不命中；未收�?K 线不计入；OI 数据不足跳过�?
## 任务 3：参数下拉框

- Modify `Analyzer`：新�?`selectParam`�?- Modify `static/js/app.js` + `style.css`：`select` 类型渲染下拉框，执行/重置逻辑兼容�?- Test：Java �?param spec 包含 options（如可测）�?
## 任务 4：配置与报告映射

- Modify `AppProperties`�? �?`StreakConfig`（enabled/interval/periods）�?- Modify `src/main/resources/application.yml` �?`config/application.yml.example`�?- Modify `ReportWriter`：`Map.of` 改为 `Map.ofEntries`，新�?3 个文件名映射�?
## 任务 5：文�?
- Modify `CLAUDE.md` / `README.md`：新增分析器、参数、周期说明�?
## 验收

- [x] `mvn test` 全绿�?- [x] 现有 88 个测试不回归�?- [x] 页面参数可选择 `1d` / `1h`�?- [x] 页面标题保持 `dome`�?