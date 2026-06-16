# ba-analysis — 币安数据分析服务

只读 MySQL 中的 K 线/持仓量/资金费率,运行各类分析器并对外提供查询接口(端口 8080)。
**从不访问币安 API。**

## 职责
- 20 个分析器(连涨、急涨、OI 连增、量价背离、综合评分等)
- 多周期特征引擎 `MarketFeatureService`
- Web 接口 `AnalysisController`、报告输出 `ReportWriter`
- 币种全集从库推导:`SELECT DISTINCT symbol FROM klines`(同步服务写了哪些币就分析哪些)

## 与 ba-sync 的关系
两个服务**完全独立**,仅通过 MySQL 交换数据。本服务只读,数据的拉取与写入、schema 的创建
全部由 `ba-sync` 负责。**首次部署需先启动 `ba-sync` 建表并灌入数据**,否则本服务读到空表。
数据读取层 `DataFetchService` 已无任何币安回退逻辑:读不到就返回空并打 warn,提示同步服务落后。

## 定时任务(application.yml `binance.schedule`,北京时间)
| 任务 | cron | 说明 |
|---|---|---|
| daily-analysis | `0 5 8 * * ?` | 每日运行非日内分析器 |
| short-term-analysis | `0 */5 * * * ?` | 每 5 分钟运行日内数据分析器 |

## 运行
```bash
mvn package -DskipTests
./start.sh            # 后台启动; 端口 8080
./start.sh log
./start.sh status
```
无需出网,只需能连到 MySQL。
