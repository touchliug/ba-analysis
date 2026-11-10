# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this service is

`ba-analysis` is a Spring Boot 3.2 / Java 21 service that **only reads** Binance USDT-M futures data (klines, open interest, funding rate, long/short ratio, ticker/premium snapshots) from MySQL, runs analyzers over it, and exposes results via REST + a static web UI over HTTPS on port 443.

The hard architectural rule: **this service never calls the Binance API.** All data fetching, writing, and DDL is handled by a separate, independent service (`ba-sync`). The two services share nothing but the MySQL database. If a table is empty or missing, that means `ba-sync` hasn't run yet — the expected behavior here is to log a warn and return empty, never to fall back to fetching from Binance.

## Commands

```bash
mvn package -DskipTests    # build the fat jar into target/ba-analysis-1.0.0.jar
mvn test                   # run all tests
mvn test -Dtest=FirstYinDayAnalyzerTest          # single test class
mvn test -Dtest=FirstYinDayAnalyzerTest#methodName   # single test method

./start.sh config  # first deploy: generate config/application.yml from config/application.yml.example
./start.sh cert    # first deploy: generate keystore.p12 (self-signed)
./start.sh setcap  # non-root deploy: allow java to bind port 443
./start.sh         # start in background (java -jar), HTTPS 443, logs to analysis.log
./start.sh log     # tail the log
./start.sh status  # check running PID
./start.sh stop | restart
```

Running requires MySQL connectivity only (no outbound internet). On deploy, `config/application.yml` (gitignored, full copy of every key) overrides the jar's `application.yml`; `start.sh` passes `--spring.config.additional-location=file:./config/` and refuses to start without it. Secrets stay as `${DB_PASSWORD}` / `${SSL_KEYSTORE_PASSWORD}` placeholders filled from `.env`, which `start.sh` sources. When adding a config key, add it to both `src/main/resources/application.yml` and `config/application.yml.example`.

## Architecture

Data flows in one direction: **MySQL → JdbcDataStore → DataFetchService → Analyzers → AnalysisReport → (ReportWriter to disk + in-memory cache for the API).**

- **`SymbolService`** reads the universe from the `symbols` table (`status='TRADING'` and `quote_asset='USDT'`), falling back to `SELECT DISTINCT symbol FROM klines WHERE interval='1d'` when the table is empty. There is no `symbols.json`. The scheduler re-reads this before **every** run (including manual `/run`), so newly listed/delisted contracts are picked up without a restart.
- **`JdbcDataStore`** is the read-only data access layer (klines, open_interest, funding_rate, long_short_ratio, ticker_24h, premium_index, symbols). No writes, no DDL.
- **`DataFetchService`** wraps `JdbcDataStore` with batch reads and a 60-second kline cache shared by analyzers in one batch. When fewer than 90% of requested symbols return data it logs a warn ("sync service may be behind") but still returns what it has.
- **`Analyzer`** interface + **`AbstractKlineAnalyzer`** base. Each analyzer is a Spring `@Component`, so Spring injects all of them as a `List<Analyzer>` into the scheduler and controller. To add an analyzer, implement the interface (usually extend `AbstractKlineAnalyzer`) and annotate `@Component` — no registration needed.
- **`AbstractStreakAnalyzer`** backs the three consecutive-scan analyzers (`价格连续上涨` / `价格连续下跌` / `OI连续上涨`). They take `interval` (`1d`/`1h`) and `periods` (default 5), use closed candles only, and tolerate 1 day of lag for daily K lines / 2 hours for hourly data. Daily OI gets 2 days because `ba-sync` writes it at 08:10 while daily-analysis starts at 08:05. OI streaks use `sum_open_interest` (coin quantity), not `sum_open_interest_value` (USDT notional).
- **`StageService`** computes the market stage (吸筹/试盘/拉升/派发/砸盘/冷却) from 120 days of daily data, OI, funding and long/short ratio, with a 60-second cache. **`ScreenerService`** reuses that series for six screeners (放量异动/持仓异动/费率极值/多空背离/缩量横盘/逼近新高). Both caches are single-flight: a cold/expired cache is loaded by one thread while concurrent requests wait and reuse the result. Stage labels are descriptive only — backtests found no predictive edge; do not present them as buy/sell signals.

### Scheduled batches (default: all disabled)

`AnalysisScheduler` runs analyzers in two cron batches, split by `Analyzer.requiresIntradayData()`:

- **daily-analysis** (`0 5 8 * * ?`, 08:05 Beijing): analyzers where `requiresIntradayData() == false` (daily-kline based).
- **short-term-analysis** (`0 */5 * * * ?`, every 5 min): analyzers where `requiresIntradayData() == true`. This includes `多空比异动` (`LongShortRatioAnalyzer`) and any streak analyzer configured with `interval=1h`. All are disabled by default, like every analyzer in the shipped config.

Every analyzer's `enabled` flag defaults to `false`; scheduled batches skip entirely when nothing is enabled. Manual API runs are still allowed for disabled analyzers.

When adding an analyzer, set `requiresIntradayData()` correctly or it will run in the wrong batch (or never, if its data interval isn't synced at that cadence). `AbstractKlineAnalyzer` derives the required history from the `days` param plus `history-buffer-days`; analyzers keyed on other params (e.g. `ReversalLongAnalyzer`) must override `fetchData()`. `AbstractStreakAnalyzer.requiresIntradayData()` follows the configured `interval`, so a streak analyzer only moves to the 5-minute batch when its config is `1h`.

### Timezone

The JVM default timezone is forced to `Asia/Shanghai` in `AnalysisApplication.main()`. All cron expressions in `application.yml` are therefore Beijing time regardless of the host's timezone. Don't remove this — analyzer date logic depends on it.

## Configuration

Everything analyzer-related is in `application.yml` under `binance.analysis.*`, bound to `AppProperties`. Each analyzer has an `enabled` flag and its own threshold params. Most analyzers ship disabled; check the yml before assuming one runs. The same params can be overridden per-request via the API.

## Web API

`AnalysisController` (`/api/analysis`) is the main surface, plus `HealthController`. Endpoints:

- `GET /list` — analyzer names (the Chinese strings).
- `POST /run/{name}` — async run using config defaults; rate-limited to once per 10s per analyzer; short-circuits if already running.
- `POST /sync/{name}` — synchronous run; accepts param overrides via query string and/or JSON body (body wins on conflict). Same 10s rate limit. Updates the in-memory cache but does **not** write a report file.
- `GET /params/{name}` — param spec for an analyzer (from `getParamSpec()`).
- `GET /latest` and `GET /latest/{name}` — read the in-memory cache of the most recent report per analyzer type. The cache is empty after a restart until an analyzer runs.
- `GET /symbols` — read the symbol universe from MySQL (re-reads on every request; never calls Binance).

`MarketController` (`/api/market`) provides read-only market data:

- `GET /lsr/{symbol}` and `GET /lsr/ranking` — long/short ratio series and ranking.
- `GET /ticker/{symbol}`, `/ticker/gainers`, `/ticker/losers`, `/ticker/volume` — 24h snapshot.
- `GET /premium/{symbol}`, `/premium/funding-ranking` — mark price / funding rate snapshot.
- `GET /stage`, `GET /stage/{symbol}` — market stage scan/detail.
- `GET /screen/types`, `GET /screen` — screener definitions and results.
- `GET /flow/{symbol}` — aligned price / taker-buy / OI / top-position series for the flow chart.
- `GET /api/market/hourly-rise-oi` — hourly scan: last N closed 1h bars all close>open and `sum_open_interest` net up in the same window (independent page, cached in memory; `periods` 2–24, `refresh=true` forces a rescan).

`GET /api/health` checks the database (`SELECT 1`) and returns 503 with `status=DOWN` when MySQL is unavailable.

**Param key casing gotcha:** `application.yml` keys are kebab-case (`threshold-percent`, `min-oi-rise-percent`), but the API override keys in `getParamSpec()` are camelCase (`thresholdPercent`, `minOiRisePercent`). When overriding via the API, use the camelCase form. `mergeParams()` coerces query-string values to Integer/Double (a value containing `.` becomes Double), so pass numbers as numbers; JSON body values keep their JSON type.

Analyzer names are **Chinese strings** (`getName()`, e.g. `首阴日`, `多空比异动`) and are used as cache keys, API path params, and report filenames. `ReportWriter.NAME_MAPPING` maps all 13 to ASCII filenames; unmapped names get a regex-sanitized fallback. Param specs can use type `select` with `options`; the page renders those as dropdowns.

## Reports

`ReportWriter` writes each scheduled/async report as a `.txt` to `./reports/yyyyMMdd/` and prunes directories older than 30 days on startup. Sync runs and the API serve results from the in-memory cache, not these files.
