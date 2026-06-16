package com.ba.analyzer.model;

import lombok.Builder;
import lombok.Data;

/**
 * 多窗口市场特征 (复刻 sanhe6 的 oiWindows + 多窗口价格/量能)。
 * 由 MarketFeatureService 基于 5m K线 + 5m/1d OI 计算产出, 供综合评分层和各分析器复用。
 *
 * 字段命名对应 sanhe6 dashboard 的 entryWindowSignal / market.oiWindows:
 * - oiChange: 持仓量各时间窗变化率(%)
 * - priceChange: 价格各时间窗变化率(%)
 * - volChange: 量能变化(近6h vs 前6h、近6h vs 30日均量)
 */
@Data
@Builder
public class MarketFeature {

    private String symbol;

    // 持仓量(OI)各窗口变化率(%)
    private double oiChangeM5;
    private double oiChangeH1;
    private double oiChangeH6;
    private double oiChangeH24;
    private double oiChangeD30;

    // 价格各窗口变化率(%)
    private double priceChangeH6;
    private double priceChangeH24;

    // 量能
    private double volChangeH6;   // 近6h成交额 / 前6h成交额 (倍数)
    private double volVs30dAvg;   // 近6h成交额折日 / 30日均量 (倍数)
    private double quoteVolume24h;

    // 资金费率
    private double fundingRate;

    // 当前价格
    private double currentPrice;

    // 30日价格分位 (0-100, 当前价在过去30天的位置; 低=低位)
    private double pricePercentile30d;

    // 派生信号判据
    private boolean contractIgnition;   // 合约点火: 长期低位 + 6h OI放量 + 5m不塌
    private boolean volumeRelay;        // 量能接力: 近6h成交额放大
    private boolean fundingHealthy;     // 费率健康: 接近中性

    /** OI数据是否可用(5m OI缺失时为false, 此时点火等判据不可信)。 */
    private boolean oiAvailable;
}
