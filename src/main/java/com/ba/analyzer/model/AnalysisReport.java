package com.ba.analyzer.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 分析报告模型
 * 每次分析执行的输出结果，包含分析类型、时间、描述、匹配的币种列表
 * 内部类CoinAnalysis表示单个匹配币种的分析详情
 * usedParams字段记录本次分析实际使用的参数，便于确认参数是否生效
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisReport {

    private String analysisType;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime analysisTime;
    private String description;
    private List<CoinAnalysis> coins;
    private int totalAnalyzed;
    private int matchedCount;
    private Map<String, Object> usedParams;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoinAnalysis {

        private String symbol;
        private double currentPrice;
        private double changePercent;
        private String detail;
        private Integer score;
        // 综合评分层专用 (其余分析器不填, 保持向后兼容)
        private String stage;                    // 入场阶段: WATCH/SETUP/WAIT_PULLBACK/ENTRY/AVOID
        private String side;                     // 方向: long/short/none
        private Map<String, Object> scoreBreakdown; // 各维度得分拆解
    }
}
