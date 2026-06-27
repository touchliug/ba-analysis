package com.ba.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 24h 行情快照模型 (对应 ticker_24h 表, 每币一行最新)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Ticker24hData {

    private String symbol;
    private String priceChangePercent;
    private String lastPrice;
    private String openPrice;
    private String highPrice;
    private String lowPrice;
    private String weightedAvgPrice;
    private String volume;
    private String quoteVolume;
    private long tradeCount;
    private long openTime;
    private long closeTime;
    private long capturedAt;

    public double getPriceChangePercentNum() { return parse(priceChangePercent); }

    public double getLastPriceNum() { return parse(lastPrice); }

    public double getQuoteVolumeNum() { return parse(quoteVolume); }

    private static double parse(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
