package com.ba.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 多空比数据模型 (对应 long_short_ratio 表)。
 * ratioType: GLOBAL_ACCOUNT / TOP_ACCOUNT / TOP_POSITION / TAKER。
 * 比率字段存 String, 由 getter 解析 double, 解析失败返回 0 (参照 OpenInterestData)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LongShortRatioData {

    private String symbol;
    private String ratioType;
    private String period;
    private long timestamp;
    private String longShortRatio;
    private String longValue;
    private String shortValue;

    public double getRatioValue() { return parse(longShortRatio); }

    public double getLongValueNum() { return parse(longValue); }

    public double getShortValueNum() { return parse(shortValue); }

    private static double parse(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
