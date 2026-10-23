# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this service is

`ba-analysis` is a Spring Boot 3.2 / Java 21 service that **only reads** Binance USDT-M futures data (klines, open interest, funding rate) from MySQL, runs a set of analyzers over it, and exposes the results via REST + a static web UI over HTTPS on port 443.

The hard architectural rule: **this service never calls the Binance API.** All data fetching, writing, and DDL is the responsibility of a separate, independent service (`ba-sync`). The two services share nothing but the MySQL database. If a table is empty or missing, that means `ba-sync` hasn't run yet — the expected behavior here is to log a warn and return empty, never to fall back to fetching from Binance.

## Commands

```bash
mvn package -DskipTests    # build the fat jar into target/ba-analysis-1.0.0.jar
mvn test                   # run all tests
mvn test -Dtest=FirstYinDayAnalyzerTest          # single test class
mvn test -Dtest=FirstYinDayAnalyzerTest#methodName   # single test method

./start.sh config  # first deploy: generate config/application.yml from config/application.yml.example
./start.sh         # start in background (java -jar), HTTPS 443, logs to analysis.log
./start.sh log     # tail the log
./start.sh status  # check running PID
./start.sh stop | restart
```

Running requires MySQL connectivity only (no outbound internet). On deploy, `config/application.yml` (gitignored, full copy of every key) overrides the jar's `application.yml`; `start.sh` passes `--spring.config.additional-location=file:./config/` and refuses to start without it. Secrets stay as `${DB_PASSWORD}` / `${SSL_KEYSTORE_PASSWORD}` placeholders filled from `.env`, which `start.sh` sources. When adding a config key, add it to both `src/main/resources/application.yml` and `config/application.yml.example`.

## Architecture

Data flows in one direction: **MySQL → DataFetchService → Analyzers → AnalysisReport → (ReportWriter to disk + in-memory cache for the API).**

- **`SymbolService`** derives the universe of symbols at runtime via `SELECT DISTINCT symbol FROM klines WHERE interval='1d'`. There is no `symbols.json` — whatever `ba-sync` has written is what gets analyzed. The scheduler re-reads this before every run so newly listed/delisted contracts are picked up automatically.
- **`JdbcDataStore`** is the read-only data access layer (klines, open_interest, funding_rate). No writes, no DDL.
- **`DataFetchService`** wraps `JdbcDataStore` with the same method signatures the analyzers and `MarketFeatureService` expect. When fewer than 90% of requested symbols return data it logs a warn ("sync service may be behind") but still returns what it has.
- **`Analyzer`** interface + **`AbstractKlineAnalyzer`** base. Each analyzer is a Spring `@Component`, so Spring injects all of them as a `List<Analyzer>` into the scheduler and controller. To add an analyzer, implement the interface (usually extend `AbstractKlineAnalyzer`) and annotate `@Component` — no registration needed.
- **`MarketFeatureService`** is a multi-window feature engine (m5/h1/h6/h24/d30 windows over 5m klines + OI) producing `MarketFeature` objects with derived signals (contract ignition, volume relay, funding health). Thresholds are calibrated from real samples — see the constants and comments at the top of the class.

### Two scheduled batches (the intraday split)

`AnalysisScheduler` runs analyzers in two cron batches, split by `Analyzer.requiresIntradayData()`:

- **daily-analysis** (`0 5 8 * * ?`, 08:05 Beijing): analyzers where `requiresIntradayData() == false` (daily-kline based).
- **short-term-analysis** (`0 */5 * * * ?`, every 5 min): analyzers where `requiresIntradayData() == true` (5m-kline based). Currently `短期急涨` (`ShortTermRiseAnalyzer`) and `综合评分` (`CompositeScoreAnalyzer`). The latter implements `Analyzer` directly (not `AbstractKlineAnalyzer`) and is driven by `MarketFeatureService`, so it doesn't fit the daily-kline preload mold — keep that in mind when touching the scheduler's preload path.

When adding an analyzer, set `requiresIntradayData()` correctly or it will run in the wrong batch (or never, if its data interval isn't synced at that cadence). `requiredDays()` tells the daily preload how much history to fetch; `AbstractKlineAnalyzer` derives it from the `days` default param, so analyzers keyed on other params must override it.

### Timezone

The JVM default timezone is forced to `Asia/Shanghai` in `AnalysisApplication.main()`. All cron expressions in `application.yml` are therefore Beijing time regardless of the host's timezone. Don't remove this — analyzer date logic depends on it.

## Configuration

Everything analyzer-related is in `application.yml` under `binance.analysis.*`, bound to `AppProperties`. Each analyzer has an `enabled` flag and its own threshold params. Most analyzers ship disabled; check the yml before assuming one runs. The same params can be overridden per-request via the API (see below).

## Web API

`AnalysisController` (`/api/analysis`) is the main surface, plus `HealthController`. Endpoints:

- `GET /list` — analyzer names (the Chinese strings).
- `GET /run/{name}` — async run using config defaults; rate-limited to once per 10s per analyzer; also short-circuits if the analyzer is disabled or already running.
- `POST /sync/{name}` — synchronous run; accepts param overrides via query string and/or JSON body (body wins on conflict). Same 10s rate limit.
- `GET /params/{name}` — param spec for an analyzer (from `getParamSpec()`).
- `GET /latest` and `GET /latest/{name}` — read the in-memory cache of the most recent report per analyzer type.
- `GET /symbols` and `POST /symbols/refresh` — read the symbol universe from MySQL (refresh just re-reads the DB; it never calls Binance).

**Param key casing gotcha:** `application.yml` keys are kebab-case (`threshold-percent`, `min-oi-rise-percent`), but the API override keys in `getParamSpec()` are camelCase (`thresholdPercent`, `minOiRisePercent`). When overriding via the API, use the camelCase form. `mergeParams()` coerces query-string values to Integer/Double (a value containing `.` becomes Double), so pass numbers as numbers; JSON body values keep their JSON type.

Analyzer names are **Chinese strings** (`getName()`, e.g. `短期急涨`, `综合评分`) and are used as cache keys, API path params, and report filenames. `ReportWriter.NAME_MAPPING` maps a subset of these to ASCII filenames; unmapped names get a regex-sanitized fallback.

## Reports

`ReportWriter` writes each report as a `.txt` to `./reports/yyyyMMdd/` and prunes directories older than 30 days on startup. This is purely an output artifact; the API serves results from the in-memory cache, not these files.
