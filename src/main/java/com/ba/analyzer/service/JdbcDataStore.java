package com.ba.analyzer.service;

import com.ba.analyzer.model.FundingRateData;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    // ======================== Kline (read-only) ========================

    public List<KlineData> getKlines(String symbol, String interval, int limit) {
        String sql = "SELECT * FROM klines WHERE symbol=? AND `interval`=? ORDER BY open_time DESC LIMIT ?";
        List<KlineData> result = jdbc.query(sql, this::mapKline, symbol, interval, limit);
        Collections.reverse(result);
        return result;
    }

    public Map<String, List<KlineData>> getKlinesBatch(List<String> symbols, String interval, int limit) {
        Map<String, List<KlineData>> result = new HashMap<>();
        for (String symbol : symbols) {
            List<KlineData> klines = getKlines(symbol, interval, limit);
            if (!klines.isEmpty()) result.put(symbol, klines);
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

    public Map<String, List<OpenInterestData>> getOpenInterestBatch(List<String> symbols, String period, int limit) {
        Map<String, List<OpenInterestData>> result = new HashMap<>();
        for (String symbol : symbols) {
            List<OpenInterestData> oi = getOpenInterestHistory(symbol, period, limit);
            if (!oi.isEmpty()) result.put(symbol, oi);
        }
        return result;
    }

    // ======================== Funding Rate (read-only) ========================

    public List<FundingRateData> getFundingRates(String symbol, int limit) {
        String sql = "SELECT * FROM funding_rate WHERE symbol=? ORDER BY funding_time DESC LIMIT ?";
        return jdbc.query(sql, (rs, n) -> {
            FundingRateData fr = new FundingRateData();
            fr.setSymbol(rs.getString("symbol"));
            fr.setFundingTime(rs.getLong("funding_time"));
            fr.setFundingRate(rs.getString("funding_rate"));
            return fr;
        }, symbol, limit);
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
}
