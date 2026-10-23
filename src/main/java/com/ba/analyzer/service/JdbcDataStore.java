package com.ba.analyzer.service;

import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.LongShortRatioData;
import com.ba.analyzer.model.OpenInterestData;
import com.ba.analyzer.model.PremiumIndexData;
import com.ba.analyzer.model.Ticker24hData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据访问层 (ba-analysis 专用) —— 只读MySQL。
 *
 * 建表与写入由同步服务(ba-sync)的 JdbcDataStore 负责; 本类不做 DDL、不做写操作。
 * 若表不存在或为空, 说明同步服务尚未运行 —— 读操作会抛异常或返回空, 这是预期行为。
 */
@Slf4j
@Service
public class JdbcDataStore {

    private final JdbcTemplate jdbc;

    public JdbcDataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 单条 IN 查询最多带多少个 symbol (超过则分批, 避免占位符过多)。 */
    static final int IN_CHUNK = 500;

    /** 生成 n 个 "?" 占位符, 逗号分隔 (用于 IN 子句)。 */
    static String inPlaceholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    /** 把列表按 size 切片 (IN 列表分批)。 */
    static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }

    // ======================== Kline (read-only) ========================

    public List<KlineData> getKlines(String symbol, String interval, int limit) {
        String sql = "SELECT * FROM klines WHERE symbol=? AND `interval`=? ORDER BY open_time DESC LIMIT ?";
        List<KlineData> result = jdbc.query(sql, this::mapKline, symbol, interval, limit);
        Collections.reverse(result);
        return result;
    }

    /**
     * 批量取多 symbol 各自最近 limit 根 K 线 —— 单条 IN + 窗口函数, 避免逐 symbol 的 N+1 查询
     * (远端库下 529 次往返曾耗时 ~130s, 现合并为按 IN_CHUNK 分批的少数几条)。
     * 每 symbol 结果按 open_time 升序 (末尾最新), 与 {@link #getKlines} 一致。
     */
    public Map<String, List<KlineData>> getKlinesBatch(List<String> symbols, String interval, int limit) {
        Map<String, List<KlineData>> result = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) return result;
        for (List<String> chunk : partition(symbols, IN_CHUNK)) {
            String sql = "SELECT * FROM (SELECT k.*, ROW_NUMBER() OVER "
                    + "(PARTITION BY symbol ORDER BY open_time DESC) rn "
                    + "FROM klines k WHERE `interval`=? AND symbol IN (" + inPlaceholders(chunk.size()) + ")) t "
                    + "WHERE t.rn <= ? ORDER BY symbol, open_time ASC";
            Object[] args = new Object[chunk.size() + 2];
            args[0] = interval;
            for (int i = 0; i < chunk.size(); i++) args[i + 1] = chunk.get(i);
            args[chunk.size() + 1] = limit;
            jdbc.query(sql, (RowCallbackHandler) rs ->
                    result.computeIfAbsent(rs.getString("symbol"), s -> new ArrayList<>())
                            .add(mapKline(rs, 0)), args);
        }
        return result;
    }

    // ======================== Open Interest (read-only) ========================

    public List<OpenInterestData> getOpenInterestHistory(String symbol, int limit) {
        return getOpenInterestHistory(symbol, "1d", limit);
    }

    public List<OpenInterestData> getOpenInterestHistory(String symbol, String period, int limit) {
        String sql = "SELECT * FROM open_interest WHERE symbol=? AND period=? ORDER BY `timestamp` DESC LIMIT ?";
        List<OpenInterestData> result = jdbc.query(sql, this::mapOi, symbol, period, limit);
        Collections.reverse(result);
        return result;
    }

    public Map<String, List<OpenInterestData>> getOpenInterestBatch(List<String> symbols, int days) {
        return getOpenInterestBatch(symbols, "1d", days);
    }

    /** 批量取多 symbol 各自最近 limit 条 OI —— 单条 IN + 窗口函数 (同 {@link #getKlinesBatch})。 */
    public Map<String, List<OpenInterestData>> getOpenInterestBatch(List<String> symbols, String period, int limit) {
        Map<String, List<OpenInterestData>> result = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) return result;
        for (List<String> chunk : partition(symbols, IN_CHUNK)) {
            String sql = "SELECT * FROM (SELECT o.*, ROW_NUMBER() OVER "
                    + "(PARTITION BY symbol ORDER BY `timestamp` DESC) rn "
                    + "FROM open_interest o WHERE period=? AND symbol IN (" + inPlaceholders(chunk.size()) + ")) t "
                    + "WHERE t.rn <= ? ORDER BY symbol, `timestamp` ASC";
            Object[] args = new Object[chunk.size() + 2];
            args[0] = period;
            for (int i = 0; i < chunk.size(); i++) args[i + 1] = chunk.get(i);
            args[chunk.size() + 1] = limit;
            jdbc.query(sql, (RowCallbackHandler) rs ->
                    result.computeIfAbsent(rs.getString("symbol"), s -> new ArrayList<>())
                            .add(mapOi(rs, 0)), args);
        }
        return result;
    }

    // ======================== Long/Short Ratio (read-only) ========================

    public List<LongShortRatioData> getLongShortRatio(String symbol, String ratioType, String period, int limit) {
        String sql = "SELECT * FROM long_short_ratio WHERE symbol=? AND ratio_type=? AND period=? " +
                "ORDER BY `timestamp` DESC LIMIT ?";
        List<LongShortRatioData> result = jdbc.query(sql, this::mapLsr, symbol, ratioType, period, limit);
        Collections.reverse(result); // 时间升序, 末尾为最新 (与 getKlines 一致)
        return result;
    }

    /** 批量取多 symbol 各自最近 limit 条多空比 —— 单条 IN + 窗口函数 (同 {@link #getKlinesBatch})。 */
    public Map<String, List<LongShortRatioData>> getLongShortRatioBatch(
            List<String> symbols, String ratioType, String period, int limit) {
        Map<String, List<LongShortRatioData>> result = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) return result;
        for (List<String> chunk : partition(symbols, IN_CHUNK)) {
            String sql = "SELECT * FROM (SELECT l.*, ROW_NUMBER() OVER "
                    + "(PARTITION BY symbol ORDER BY `timestamp` DESC) rn "
                    + "FROM long_short_ratio l WHERE ratio_type=? AND period=? "
                    + "AND symbol IN (" + inPlaceholders(chunk.size()) + ")) t "
                    + "WHERE t.rn <= ? ORDER BY symbol, `timestamp` ASC";
            Object[] args = new Object[chunk.size() + 3];
            args[0] = ratioType;
            args[1] = period;
            for (int i = 0; i < chunk.size(); i++) args[i + 2] = chunk.get(i);
            args[chunk.size() + 2] = limit;
            jdbc.query(sql, (RowCallbackHandler) rs ->
                    result.computeIfAbsent(rs.getString("symbol"), s -> new ArrayList<>())
                            .add(mapLsr(rs, 0)), args);
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

    // ======================== Row Mappers ========================

    private KlineData mapKline(ResultSet rs, int rowNum) throws SQLException {
        KlineData k = new KlineData();
        k.setOpenTime(rs.getLong("open_time"));
        k.setOpen(rs.getString("open"));
        k.setHigh(rs.getString("high"));
        k.setLow(rs.getString("low"));
        k.setClose(rs.getString("close"));
        k.setVolume(rs.getString("volume"));
        k.setCloseTime(rs.getLong("close_time"));
        k.setQuoteAssetVolume(rs.getString("quote_asset_volume"));
        k.setNumberOfTrades(rs.getInt("number_of_trades"));
        k.setTakerBuyBaseAssetVolume(rs.getString("taker_buy_base"));
        k.setTakerBuyQuoteAssetVolume(rs.getString("taker_buy_quote"));
        return k;
    }

    private OpenInterestData mapOi(ResultSet rs, int rowNum) throws SQLException {
        OpenInterestData oi = new OpenInterestData();
        oi.setSymbol(rs.getString("symbol"));
        oi.setTimestamp(rs.getLong("timestamp"));
        oi.setOpenInterest(rs.getString("open_interest"));
        oi.setSumOpenInterest(rs.getString("sum_open_interest"));
        oi.setSumOpenInterestValue(rs.getString("sum_open_interest_value"));
        return oi;
    }

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
        // 注: `time` 是 MySQL 保留字, 任何 WHERE/ORDER BY 引用它时需反引号转义
        p.setTime(rs.getLong("time"));
        return p;
    }
}
