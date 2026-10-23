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
        // 分析窗口(天)
        private int days = 14;
        // 最低总分
        private int minScore = 60;
        // 价格位置: 回溯天数
        private int priceLookbackDays = 30;
        // 价格位置: 最低回撤% (从回溯期高点)
        private double drawdownMin = 10.0;
        // 低点抬高: true 时后半段最低点必须高于前半段最低点, 否则不输出
        private boolean requireHigherLow = true;
        // 量能回升: 后半段均量/前半段均量 >= 此值
        private double volumeIncreaseMin = 1.2;
        // 振幅收敛: 后半段振幅/前半段振幅 <= 此值
        private double rangeContractionMax = 0.8;
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
        private boolean enabled = true;     // 默认启用, 随 short-term 批次每5分钟跑
        private String period = "1h";       // LSR 统计周期 (库内默认 1h)
        private int lookback = 12;           // 与多少根之前比较 (变化率/背离)
        private int minScore = 60;           // 低于此分不输出
        // 维度权重 (合计 100)
        private int topPositionWeight = 40;  // 大户持仓多空比抬升
        private int takerWeight = 35;        // taker 主动买盘占优
        private int divergenceWeight = 25;   // 大户进 + 散户退 背离
        private double takerBullishMin = 1.0; // taker 买卖比超过此值算买盘占优
    }

    @Data
    public static class ScheduleConfig {
        private String dailyAnalysis = "0 5 8 * * ?";
        private String shortTermAnalysis = "0 */5 * * * ?";
    }

    @Data
    public static class ReportConfig {
        private String dir = "./reports";
    }
}
