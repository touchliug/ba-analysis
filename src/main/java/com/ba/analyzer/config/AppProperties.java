package com.ba.analyzer.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "binance")
public class AppProperties {

    private ConcurrencyConfig concurrency = new ConcurrencyConfig();
    private AnalysisConfig analysis = new AnalysisConfig();
    private ScheduleConfig schedule = new ScheduleConfig();
    private ReportConfig report = new ReportConfig();
    private HourlyScanConfig hourlyScan = new HourlyScanConfig();
    private SetupScanConfig setupScan = new SetupScanConfig();

    /** 原币安客户端时代的并发配置, 拆分后只剩 historyBufferDays 被分析器使用。 */
    @Data
    public static class ConcurrencyConfig {
        @Min(1)
        private int historyBufferDays = 30;
    }

    @Data
    public static class AnalysisConfig {
        private ConsecutiveRiseConfig consecutiveRise = new ConsecutiveRiseConfig();
        private HighRiseConfig highRise = new HighRiseConfig();
        private LowPriceConsolidationConfig lowPriceConsolidation = new LowPriceConsolidationConfig();
        private BullishAccumulationConfig bullishAccumulation = new BullishAccumulationConfig();
        private FirstYinDayConfig firstYinDay = new FirstYinDayConfig();
        private NDayRangeConfig nDayRange = new NDayRangeConfig();
        private OiDivergenceConfig oiDivergence = new OiDivergenceConfig();
        private AltcoinPumpAlertConfig altcoinPumpAlert = new AltcoinPumpAlertConfig();
        private ReversalLongConfig reversalLong = new ReversalLongConfig();
        private LongShortRatioConfig longShortRatio = new LongShortRatioConfig();
        private StreakConfig priceRiseStreak = new StreakConfig();
        private StreakConfig priceFallStreak = new StreakConfig();
        private StreakConfig oiRiseStreak = new StreakConfig();
    }

    /** 连续上涨 = 首阴日候选池 (已合并原"缓涨币"): 连涨中的强势币, 收阴即可能触发首阴日做空。 */
    @Data
    public static class ConsecutiveRiseConfig {
        private boolean enabled = true;
        // 最少连续阳线天数 (默认与首阴日对齐)
        private int days = 3;
        // 单根阳线最小实体涨幅(%): 低于此值不算有效阳线, 过滤十字星/稳定币微动
        private double minDailyRisePercent = 0.5;
        // 单根阳线最大实体涨幅(%), 0=不限; 设为3即原"缓涨币"(温和连涨)
        private double maxDailyRisePercent = 0;
        // 连涨区间累计涨幅下限(%, 复利), 0=不过滤
        private double minTotalRisePercent = 0;
        // 连涨期均成交额/之前均额 >= 此值才输出 (1.0=不过滤)
        private double volumeSurgeRatio = 1.0;
        // 量比上限, 0=不限; 设<1即要求缩量连涨(原"缓涨币"的缩量蓄力)
        private double maxVolumeRatio = 0;
    }

    @Data
    public static class HighRiseConfig {
        private boolean enabled = true;
        private int days = 7;
        private double thresholdPercent = 50;
        private double volumeSurgeRatio = 1.0;
    }

    /** 低位插针: 低位横盘中出现长下影(下探被买回), 支撑测试信号。 */
    @Data
    public static class LowPriceConsolidationConfig {
        private boolean enabled = true;
        // 横盘观察窗口(天)
        private int days = 7;
        // 窗口内价格整体变化上限(%, 绝对值)
        private double maxPriceChangePercent = 10;
        // 低位判定: 现价 <= 回看期收盘价的该分位数
        private double lowPricePercentile = 30;
        // 低位回看天数 (历史不足一半的币跳过)
        private int lowLookbackDays = 90;
        // 插针: 下影 >= 实体 × 此倍数
        private double wickBodyRatio = 2.0;
        // 插针: 下影 >= 当日振幅 × 此比例
        private double minWickRangeRatio = 0.5;
        // 插针日成交额/窗口均额 >= 此值才输出 (1.0=不过滤)
        private double volumeSurgeRatio = 1.0;
    }

    @Data
    public static class BullishAccumulationConfig {
        private boolean enabled = false;
        // 结构与价格分位回看天数
        private int lookbackDays = 60;
        // 最低蓄力评分(基础分30, 满分100)
        private int minScore = 65;
        // 二波缩量坑: 前波5日最小涨幅%
        private double minWaveRisePercent = 40.0;
        // 二波缩量坑: 距前高最小回撤%
        private double waveDrawdownMin = 25.0;
        // 二波缩量坑: 距前高最大回撤%
        private double waveDrawdownMax = 55.0;
        // 二波缩量坑: 近5日均额 / 峰值5日均额上限
        private double maxDryVolumeRatio = 0.15;
        // 首波潜伏坑: 现价所处回看期收盘价分位数上限%
        private double lowPricePercentile = 45.0;
        // 首波潜伏坑: 5日币本位OI最小净增%
        private double minOiRisePercent = 15.0;
        // 当日涨幅上限%(超过视为已启动, 不再留在潜伏观察池)
        private double maxDailyRisePercent = 8.0;
        // 20日平均日成交额下限(USDT, 0表示不过滤)
        private double minLiquidityUsdt = 2_000_000.0;
    }

    /** 首阴日 = 见顶做空信号: 连涨N天后首根阴线, 次日开盘做空, 止损放在冲高最高点上方。 */
    @Data
    public static class FirstYinDayConfig {
        private boolean enabled = false;
        // 首阴前最少连续阳线天数
        private int days = 3;
        // 连涨区间累计涨幅下限(%, 复利)
        private double minTotalRisePercent = 15;
        // 首阴相对前日收盘的最小跌幅(%): 过滤十字星式小阴线
        private double minYinDropPercent = 2.0;
        // 首阴成交额/连涨期均额 >= 此值才输出 (1.0=不过滤)
        private double volumeSurgeRatio = 1.0;
        // 止盈: 入场价下方百分比
        private double takeProfitPct = 10.0;
        // 止损: 冲高最高价上方缓冲百分比
        private double stopBufferPct = 1.0;
        // 入场到止损的距离上限(%), 超出视为风险过大不输出 (0=不限)
        private double maxStopLossPct = 15.0;
        // 见顶强度分最低分(0-100), 0=不过滤仅排序
        private int minScore = 0;
    }

    /** N日区间: 合并原 N日高低点 + N日最低。列出区间高低点、现价位置, 标记豹子号/整数关口。 */
    @Data
    public static class NDayRangeConfig {
        private boolean enabled = false;
        private int days = 7;
        // 现价在区间中的位置(%)筛选范围: 0=最低点, 100=最高点
        private double minPositionPercent = 0;
        private double maxPositionPercent = 100;
        // 只输出带豹子号/整数关口标记的币
        private boolean onlyTagged = false;
    }

    /** 持仓背离: 合并原 持仓量连增 + 价涨仓增。持仓量明显增加而价格不跟, 按价格方向判断偏多/偏空。 */
    @Data
    public static class OiDivergenceConfig {
        private boolean enabled = false;
        private int days = 5;
        // 窗口内持仓量(币本位)总增幅下限(%)
        private double minOiRisePercent = 15;
        // 窗口内持仓量上涨的最少天数
        private int minOiUpDays = 4;
        // 窗口内价格变化(%, 收盘对收盘)允许范围
        private double minPriceChangePercent = -10;
        private double maxPriceChangePercent = 10;
        // 单日增仓占比上限(0-1]: 最大单日增仓 / 全部增仓日合计。
        // 超过该值说明增幅集中在单日(爆仓/对锁等一次性事件), 不算持续建仓; <=0 关闭该过滤。
        private double maxSingleDayOiShare = 0.5;
    }

    @Data
    public static class AltcoinPumpAlertConfig {
        private boolean enabled = false;
        private int days = 7;
        private int minScore = 65;
        // 窗口内价格跌幅上限(%), 防止接飞刀. 放宽以捕捉趋势延续型暴涨
        private double maxPriceDeclinePercent = 15.0;
        private int oiConsecutiveDays = 3;
        private double volumeExpansionRatio = 1.5;
        private double lowPricePercentile = 35.0;
        private double wickBodyRatio = 1.5;
        private double silentBuyerRatio = 1.3;
        // 窗口内最大回撤上限(%), 排除仍在暴跌的币
        private double maxDrawdownPercent = 25.0;
    }

    @Data
    public static class ReversalLongConfig {
        private boolean enabled = false;
        // 连跌检测: 最少连续下跌天数
        private int declineMinDays = 4;
        // 累计跌幅范围(%): 绝对值, 下限
        private double declineMinPct = 5.0;
        // 累计跌幅范围(%): 绝对值, 上限
        private double declineMaxPct = 12.0;
        // 反转日涨幅下限(%)
        private double reversalMinPct = 1.0;
        // 反转日涨幅上限(%)
        private double reversalMaxPct = 4.0;
        // 止盈点位(%)
        private double takeProfitPct = 6.0;
        // 止损点位(%)
        private double stopLossPct = 4.0;
        // 反转日量能确认: 反转日成交量/跌势均量 >= 此值才有效 (1.0=不启用)
        private double volumeConfirmRatio = 1.0;
        // 反转质量评分最低分(0-100): 低于此分的信号过滤掉 (0=不过滤,仅排序)。
        // 回测(2739笔)显示: >=55胜率55%, >=65胜率60%, >=75胜率62%。生产默认55。
        private int qualityMinScore = 55;
    }

    @Data
    public static class LongShortRatioConfig {
        private boolean enabled = false;    // 默认关闭, 开关由 application.yml 控制
        private String period = "1h";       // LSR 统计周期 (库内默认 1h)
        private int lookback = 12;           // 与多少根之前比较 (变化率/背离)
        private int minScore = 60;           // 低于此分不输出
        // 维度权重 (合计 100)
        private int topPositionWeight = 40;  // 大户持仓多空比抬升
        private int takerWeight = 35;        // taker 主动买盘占优
        private int divergenceWeight = 25;   // 大户进 + 散户退 背离
        private double takerBullishMin = 1.0; // taker 买卖比超过此值算买盘占优
    }

    /** 连续扫描通用配置: 周期(1d/1h) + 连续根数。 */
    @Data
    public static class StreakConfig {
        private boolean enabled = false;
        private String interval = "1d";
        private int periods = 5;
    }

    @Data
    public static class ScheduleConfig {
        private String dailyAnalysis = "0 5 8 * * ?";
        private String shortTermAnalysis = "0 */5 * * * ?";
        /** 整点后 10 分钟: 等 ba-sync 把上一个整点的 1h OI 写入库。 */
        private String hourlyScan = "0 10 * * * ?";
        /** 整点后 40 分钟: 与 hourly-scan 错开, 避免两轮全市场扫描撞在一起。 */
        private String setupScan = "0 40 * * * ?";
    }

    /** 每小时扫描: 最近 N 根已收盘 1h 线连阳 + 同窗口 sum_open_interest 净增。 */
    @Data
    public static class HourlyScanConfig {
        private boolean enabled = true;
        @Min(2)
        private int periods = 4;
    }

    /**
     * 启动扫描: 四个「行情启动」捕捉器共用一次全市场加载。
     *
     * <p>与 hourly-scan 的区别: hourly-scan 要求连阳 + OI 增, 只能抓已经走出来的中段;
     * 这里抓的是点火、旗形、回踩确认、以及点火前的散户空头拥挤。
     */
    @Data
    public static class SetupScanConfig {
        private boolean enabled = true;
        private LiquidityConfig liquidity = new LiquidityConfig();
        private VolumeSpikeConfig volumeSpike = new VolumeSpikeConfig();
        private FlagBreakoutConfig flagBreakout = new FlagBreakoutConfig();
        private PullbackConfirmConfig pullbackConfirm = new PullbackConfirmConfig();
        private RetailShortConfig retailShort = new RetailShortConfig();
    }

    /** 流动性门槛: 24h 成交额太小的币, 技术形态没有交易意义 (几万美元就能插针)。 */
    @Data
    public static class LiquidityConfig {
        private double minQuoteVolume24h = 2_000_000;
    }

    /** 放量突破: 单根 1h 涨幅 + 量能倍数 + 站上 N 日高。 */
    @Data
    public static class VolumeSpikeConfig {
        /** 均量基准: 触发根之前取多少根 1h。 */
        private int avgBars = 24;
        /** 触发根成交额 / 基准均量 >= 此倍数。 */
        private double minVolumeMultiple = 10.0;
        /** 触发根涨幅下限 (%), 按 close/open。 */
        private double minChangePercent = 5.0;
        /** 突破参考位: 前 N 个交易日最高价。 */
        private int breakoutDays = 20;
        /** true = 必须站上 N 日高才算 (纯放量不突破不出信号)。 */
        private boolean requireBreakout = true;
    }

    /** 高位旗形: 一波大涨后窄幅横盘, 放量突破旗形上沿。 */
    @Data
    public static class FlagBreakoutConfig {
        /** 前置涨幅回看天数 (日线)。 */
        private int priorGainDays = 30;
        /** 前置涨幅下限 (%), 过滤没有主升浪的币。 */
        private double minPriorGainPercent = 100.0;
        /** 旗形由多少根 1h 组成。 */
        private int flagBars = 10;
        /** 旗形振幅上限 (%), (高-低)/低。 */
        private double maxFlagRangePercent = 8.0;
        /** 突破根量能 / 旗形均量 >= 此倍数。 */
        private double minBreakoutVolumeMultiple = 1.5;
        /** 目标 = 旗形上沿 + 旗高 × 此倍数 (measured move)。 */
        private double targetFlagHeights = 2.0;
    }

    /** 突破回踩: 脉冲后回踩 38.2%~78.6%, 不破启动前收盘, 放量收阳确认。 */
    @Data
    public static class PullbackConfirmConfig {
        /** 在最近多少根 1h 里找脉冲。 */
        private int spikeLookbackBars = 48;
        /** 脉冲定义: 单根涨幅下限 (%). */
        private double minSpikeChangePercent = 30.0;
        /** 脉冲定义: 成交额 / 前 24 根均量 >= 此倍数。 */
        private double minSpikeVolumeMultiple = 20.0;
        /** 回踩起点 (38.2%)。 */
        private double minRetrace = 0.382;
        /** 回踩上限 (78.6%); 超过说明结构已破。 */
        private double maxRetrace = 0.786;
        /** 回踩段均量 / 脉冲根量 <= 此值 (缩量回踩)。 */
        private double maxPullbackVolumeRatio = 0.5;
        /** 确认根量能 / 前 6 根均量 >= 此倍数。 */
        private double minConfirmVolumeRatio = 1.0;
    }

    /** 散户空头拥挤: 点火前的观察名单, 不是买点。 */
    @Data
    public static class RetailShortConfig {
        private String period = "1h";
        /** 散户账户多空比上限 (longAccounts/shortAccounts)。0.55 ≈ 35% 做多。 */
        private double maxRetailRatio = 0.55;
        /** 大户持仓多空比下限。 */
        private double minTopPositionRatio = 1.3;
        /** 大户比相对多少根之前抬升。 */
        private int topRiseLookback = 6;
        /** OI 24h 变化下限 (%), 低于此值说明在去杠杆。 */
        private double minOiChange24hPercent = -5.0;
    }

    @Data
    public static class ReportConfig {
        private String dir = "./reports";
    }
}
