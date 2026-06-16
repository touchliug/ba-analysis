package com.ba.analyzer.analysis.support;

import com.ba.analyzer.model.MarketFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PullbackGateTest {

    private MarketFeature.MarketFeatureBuilder base() {
        return MarketFeature.builder()
                .symbol("TESTUSDT")
                .oiAvailable(true)
                .fundingRate(0.0001)
                .fundingHealthy(true);
    }

    @Test
    void crash_shouldAvoid() {
        MarketFeature f = base().priceChangeH24(-25).build();
        PullbackGate.GateResult r = PullbackGate.evaluate(f);
        assertEquals(PullbackGate.EntryStage.AVOID, r.stage());
    }

    @Test
    void bigPump_shouldWaitPullback() {
        // 24h涨20%, 6h还在涨(没回踩) → 等回踩
        MarketFeature f = base().priceChangeH24(20).priceChangeH6(5).oiChangeH6(10).build();
        PullbackGate.GateResult r = PullbackGate.evaluate(f);
        assertEquals(PullbackGate.EntryStage.WAIT_PULLBACK, r.stage());
    }

    @Test
    void pumpThenPullbackOiHolds_shouldEntry() {
        // 24h涨20%后, 6h回落且OI未塌 → 二次确认入场
        MarketFeature f = base().priceChangeH24(20).priceChangeH6(-3).oiChangeH6(2).build();
        PullbackGate.GateResult r = PullbackGate.evaluate(f);
        assertEquals(PullbackGate.EntryStage.ENTRY, r.stage());
    }

    @Test
    void pumpThenPullbackOiCollapse_shouldStillWait() {
        // 回踩但OI崩了 → 不入场, 继续等
        MarketFeature f = base().priceChangeH24(20).priceChangeH6(-3).oiChangeH6(-10).build();
        PullbackGate.GateResult r = PullbackGate.evaluate(f);
        assertEquals(PullbackGate.EntryStage.WAIT_PULLBACK, r.stage());
    }

    @Test
    void ignitionWithRelay_shouldEntry() {
        // 涨幅正常 + 合约点火 + 量能接力 + 费率健康 → 入场窗口
        MarketFeature f = base()
                .priceChangeH24(6).priceChangeH6(4)
                .oiChangeH6(18).oiChangeM5(0.5).oiChangeD30(-50)
                .contractIgnition(true).volumeRelay(true)
                .build();
        PullbackGate.GateResult r = PullbackGate.evaluate(f);
        assertEquals(PullbackGate.EntryStage.ENTRY, r.stage());
    }

    @Test
    void oiIgnitionNoRelay_shouldSetup() {
        // OI点火但量能未接力 → 重点候选
        MarketFeature f = base()
                .priceChangeH24(6).oiChangeH6(12)
                .contractIgnition(false).volumeRelay(false)
                .build();
        PullbackGate.GateResult r = PullbackGate.evaluate(f);
        assertEquals(PullbackGate.EntryStage.SETUP, r.stage());
    }

    @Test
    void quiet_shouldWatch() {
        MarketFeature f = base().priceChangeH24(2).oiChangeH6(1).build();
        PullbackGate.GateResult r = PullbackGate.evaluate(f);
        assertEquals(PullbackGate.EntryStage.WATCH, r.stage());
    }

    @Test
    void nullFeature_shouldWatch() {
        assertEquals(PullbackGate.EntryStage.WATCH, PullbackGate.evaluate(null).stage());
    }
}
