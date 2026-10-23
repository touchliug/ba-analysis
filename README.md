# ba-analysis — 币安数据分析服务

只读 MySQL 中的 K 线/持仓量/多空比,运行各类分析器并对外提供查询接口。
**从不访问币安 API。**

## 职责
- 15 个分析器(连涨、OI 连增、底部吸筹、山寨币暴涨预警、反转做多、多空比异动等),
  默认全部禁用,经网页或 API 手动触发
- 行情查询接口 `MarketController`(多空比/涨跌幅/成交额/资金费率排行)
- 分析接口 `AnalysisController`、报告输出 `ReportWriter`
- 币种全集从库推导:`symbols` 表为主、`klines` 去重兜底(同步服务写了哪些币就分析哪些)

> 5m 日内链路(短期急涨/综合评分/MarketFeatureService)已移除:ba-sync 不同步 5m 数据,
> 该链路只能产出空报告。若将来需要,先在 ba-sync 补 5m K线/OI 同步再恢复。

## 与 ba-sync 的关系
两个服务**完全独立**,仅通过 MySQL 交换数据。本服务只读,数据的拉取与写入、schema 的创建
全部由 `ba-sync` 负责。**首次部署需先启动 `ba-sync` 建表并灌入数据**,否则本服务读到空表。
数据读取层 `DataFetchService` 已无任何币安回退逻辑:读不到就返回空并打 warn,提示同步服务落后。

## 定时任务(application.yml `binance.schedule`,北京时间)
| 任务 | cron | 说明 |
|---|---|---|
| daily-analysis | `0 5 8 * * ?` | 每日运行日线级分析器(有启用的才跑) |
| short-term-analysis | `0 */5 * * * ?` | 每 5 分钟运行日内批次(现仅多空比异动;有启用的才跑) |

## 运行
```bash
mvn package -DskipTests
./start.sh cert       # 首次: 生成自签名证书 keystore.p12 (不入库)
./start.sh setcap     # 非 root 部署: 授权 java 绑定 443 (一次性; 或用 sudo 启动)
./start.sh            # 后台启动; HTTPS, 端口 443
./start.sh log
./start.sh status
```
无需出网,只需能连到 MySQL。访问 `https://<host>/`(自签名证书,浏览器首次需手动信任)。

### HTTPS / 端口 443 说明
- SSL 配置在 `application.yml` 的 `server.ssl.*`,读 `./keystore.p12`(自签名,`.gitignore` 已忽略,不入库)。
- 证书生成命令封装在 `./start.sh cert`(`keytool` 生成 PKCS12,SAN 含公网 IP + localhost,有效期 10 年)。
- 443 是特权端口:非 root 启动需先 `./start.sh setcap`(底层 `setcap cap_net_bind_service`),或直接 `sudo ./start.sh`。生产更推荐用 Nginx 反代在 443 终止 SSL。
- 自签名证书浏览器会提示"不安全";要消除需换正式 CA 证书(如 Let's Encrypt),把 `key-store` 指向对应密钥库即可。
- 敏感口令(`DB_PASSWORD`、`SSL_KEYSTORE_PASSWORD`)放本地 `.env`(不入库),`start.sh` 启动时自动加载;`SSL_KEYSTORE_PASSWORD` 默认 `changeit`。
