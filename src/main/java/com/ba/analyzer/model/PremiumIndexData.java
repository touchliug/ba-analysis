package com.ba.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 标记价/溢价指数快照模型 (对应 premium_index 表, 每币一行最新)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PremiumIndexData {

    private String symbol;
    private String markPrice;
    private String indexPrice;
    private String estimatedSettlePrice;
    private String lastFundingRate;
    private long nextFundingTime;
    private String interestRate;
    private long time;

    public double getMarkPriceNum() { return parse(markPrice); }

    public double getLastFundingRateNum() { return parse(lastFundingRate); }

    private static double parse(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
