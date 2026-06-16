package com.ba.analyzer.analysis.support;

import com.ba.analyzer.model.MarketFeature;

/**
 * 回踩/追高保护门 (Phase 3, 复刻 sanhe6 的 WAIT_PULLBACK 机制)。
 *
 * 核心思想: 涨太多不追第一波, 等回踩且 OI 不塌再二次确认入场。
 * 由综合评分层调用, 也可被追涨型分析器复用。无状态工具类。
 *
 * 阶段流转:
 *   WATCH       — 观察, 尚无明确机会
 *   SETUP       — 重点候选, 结构成型但未到入场
 *   WAIT_PULLBACK — 24h涨幅过大, 仅允许回踩后二次确认
 *   ENTRY       — 入场窗口 (回踩企稳 + OI不塌 + 量能仍在)
 *   AVOID       — 回避 (暴跌中 / 数据不支持)
 */
public final class PullbackGate {

    private PullbackGate() {}

    public enum EntryStage {
        WATCH, SETUP, WAIT_PULLBACK, ENTRY, AVOID
    }

    public record GateResult(EntryStage stage, String reason) {}

    // 阈值
    private static final double PULLBACK_TRIGGER_24H = 15.0; // 24h涨幅超此值 → 等回踩
    private static final double AVOID_CRASH_24H = -20.0;     // 24h跌幅超此值 → 暴跌回避
    private static final double OI_NOT_COLLAPSE_H6 = -5.0;   // 回踩时6h OI不塌下限
    private static final double SETUP_OI_H6_MIN = 8.0;       // 候选所需6h OI增幅
    private static final double ENTRY_PULLBACK_FROM_H6 = -2.0; // 自6h高位回落(价格h6为负即回踩中)

    /**
     * 评估入场阶段。
     *
     * @param f 多窗口市场特征
     */
    public static GateResult evaluate(MarketFeature f) {
        if (f == null) {
            return new GateResult(EntryStage.WATCH, "无特征数据");
        }

        double p24 = f.getPriceChangeH24();
        double p6 = f.getPriceChangeH6();
        double oi6 = f.getOiChangeH6();

        // 暴跌中: 回避 (不接飞刀)
        if (p24 <= AVOID_CRASH_24H) {
            return new GateResult(EntryStage.AVOID,
                    String.format("24h跌幅%.1f%%, 仍在暴跌, 回避", p24));
        }

        // 追高保护: 24h涨幅过大, 强制降级为等回踩
        if (p24 >= PULLBACK_TRIGGER_24H) {
            // 已经在回踩(6h转跌)且OI不塌 → 二次确认入场
            if (f.isOiAvailable() && p6 <= ENTRY_PULLBACK_FROM_H6 && oi6 >= OI_NOT_COLLAPSE_H6) {
                return new GateResult(EntryStage.ENTRY,
                        String.format("24h涨%.1f%%后回踩(6h %.1f%%)且OI未塌(6h %.1f%%), 二次确认入场", p24, p6, oi6));
            }
            return new GateResult(EntryStage.WAIT_PULLBACK,
                    String.format("24h涨%.1f%%, 仅允许回踩后的二次确认", p24));
        }

        // 涨幅正常区间: 看OI是否点火
        if (f.isContractIgnition() || (f.isOiAvailable() && oi6 >= SETUP_OI_H6_MIN)) {
            // 早期 + OI点火 + 量能接力 → 入场窗口
            if (f.isVolumeRelay() && f.isFundingHealthy()) {
                return new GateResult(EntryStage.ENTRY,
                        String.format("早期(24h %.1f%%)+合约点火(6h OI %.1f%%)+量能接力+费率健康, 入场窗口", p24, oi6));
            }
            return new GateResult(EntryStage.SETUP,
                    String.format("OI点火(6h %.1f%%)但量能/费率待确认, 重点候选", oi6));
        }

        return new GateResult(EntryStage.WATCH, "结构未成型, 观察");
    }
}
