package com.ba.analyzer.service;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.FundingRateData;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.MarketFeature;
import com.ba.analyzer.model.OpenInterestData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多窗口市场特征引擎 (Phase 1)。
 *
 * 复刻 sanhe6 的 oiWindows(m5/h1/h6/h24/d30) + 多窗口价格/量能, 产出 {@link MarketFeature}。
 * 数据来源:
 * - 5m K线: 推导 6h/24h 价格与量能 (6h=72根, 24h=288根)
 * - 5m OI:  推导 m5/h1/h6/h24 持仓量变化
 * - 1d OI:  推导 d30 持仓量变化
 * - 资金费率: 取最新一条
 *
 * 阈值默认值参照 research/sanhe6/dash_public.json 的真实样本校准 (INIT: OI6h+18% 判为合约点火)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketFeatureService {

    private final DataFetchService dataFetchService;
    private final AppProperties appProperties;

    // 5m K线每窗口根数
    private static final int BARS_6H = 72;
    private static final int BARS_24H = 288;
    // 5m OI每窗口根数 (与K线一致)
    private static final int OI_M5 = 1;
    private static final int OI_H1 = 12;
    private static final int OI_H6 = 72;
    private static final int OI_H24 = 288;

    // 判据阈值 (校准自 sanhe6 真实样本)
    private static final double IGNITION_OI_H6_MIN = 8.0;    // 6h OI增幅阈值
    private static final double IGNITION_OI_M5_FLOOR = -1.0; // 5m OI不塌下限
    private static final double IGNITION_D30_LOW = 0.0;      // 30d OI仍低位(净减或微增)
    private static final double VOLUME_RELAY_MIN = 2.0;      // 近6h/前6h 量能放大倍数
    private static final double FUNDING_NEUTRAL_ABS = 0.0005; // 费率中性绝对值带

    /**
     * 批量计算特征。先确保 5m K线、5m OI、1d OI、资金费率已就绪(走DB缓存或拉取),再逐symbol计算。
     */
    public Map<String, MarketFeature> computeFeatures(List<String> symbols) {
        Map<String, List<KlineData>> kline5m =
                dataFetchService.fetchKlinesByInterval(symbols, "5m", BARS_24H);
        Map<String, List<OpenInterestData>> oi5m =
                dataFetchService.fetchOiHistoryByPeriod(symbols, "5m", OI_H24);
        Map<String, List<OpenInterestData>> oi1d =
                dataFetchService.fetchOiHistoryByPeriod(symbols, "1d", 30);

        Map<String, MarketFeature> result = new ConcurrentHashMap<>();
        for (String symbol : symbols) {
            List<KlineData> k = kline5m.get(symbol);
            if (k == null || k.isEmpty()) continue;
            MarketFeature f = computeOne(symbol, k, oi5m.get(symbol), oi1d.get(symbol));
            if (f != null) result.put(symbol, f);
        }
        log.info("Computed market features for {}/{} symbols", result.size(), symbols.size());
        return result;
    }

    MarketFeature computeOne(String symbol, List<KlineData> k5m,
                             List<OpenInterestData> oi5m, List<OpenInterestData> oi1d) {
        if (k5m == null || k5m.isEmpty()) return null;
        int n = k5m.size();
        KlineData latest = k5m.get(n - 1);
        double currentPrice = latest.getClosePrice();

        MarketFeature.MarketFeatureBuilder b = MarketFeature.builder()
                .symbol(symbol)
                .currentPrice(currentPrice);

        // 价格窗口
        b.priceChangeH6(pctChange(priceAgo(k5m, BARS_6H), currentPrice));
        b.priceChangeH24(pctChange(priceAgo(k5m, BARS_24H), currentPrice));

        // 量能: 近6h成交额 vs 前6h成交额
        double recent6h = sumQuoteVol(k5m, n - BARS_6H, n);
        double prev6h = sumQuoteVol(k5m, n - 2 * BARS_6H, n - BARS_6H);
        b.volChangeH6(prev6h > 0 ? recent6h / prev6h : 0);

        // 24h成交额 (近288根) + vs 30日均量(用近6h折算日量对比, 缺日线量则用24h量近似)
        double quoteVol24h = sumQuoteVol(k5m, n - BARS_24H, n);
        b.quoteVolume24h(quoteVol24h);
        double recent6hDailyized = recent6h * 4; // 6h→日
        b.volVs30dAvg(quoteVol24h > 0 ? recent6hDailyized / quoteVol24h : 0);

        // 价格30日分位 (用5m窗口内近似; 真正30d需日线, 此处用24h窗口高低位作为快速近似)
        b.pricePercentile30d(percentileInWindow(k5m, currentPrice));

        // OI窗口
        boolean oiOk = oi5m != null && oi5m.size() >= OI_H6;
        b.oiAvailable(oiOk);
        if (oiOk) {
            double oiNow = oiVal(oi5m, oi5m.size() - 1);
            b.oiChangeM5(pctChange(oiAgo(oi5m, OI_M5), oiNow));
            b.oiChangeH1(pctChange(oiAgo(oi5m, OI_H1), oiNow));
            b.oiChangeH6(pctChange(oiAgo(oi5m, OI_H6), oiNow));
            if (oi5m.size() >= OI_H24) {
                b.oiChangeH24(pctChange(oiAgo(oi5m, OI_H24), oiNow));
            }
        }
        if (oi1d != null && oi1d.size() >= 2) {
            double oiNow = oiVal(oi1d, oi1d.size() - 1);
            b.oiChangeD30(pctChange(oi1d.get(0).getOpenInterestValue(), oiNow));
        }

        // 资金费率
        double funding = latestFunding(symbol);
        b.fundingRate(funding);

        MarketFeature f = b.build();
        // 派生判据
        f.setContractIgnition(oiOk
                && f.getOiChangeH6() >= IGNITION_OI_H6_MIN
                && f.getOiChangeM5() >= IGNITION_OI_M5_FLOOR
                && f.getOiChangeD30() <= IGNITION_D30_LOW);
        f.setVolumeRelay(f.getVolChangeH6() >= VOLUME_RELAY_MIN);
        f.setFundingHealthy(Math.abs(funding) <= FUNDING_NEUTRAL_ABS);
        return f;
    }

    private double latestFunding(String symbol) {
        // 通过DataFetchService没有直接读取单条接口, 此处借用其底层store经由batch不划算;
        // 资金费率非核心权重, 缺失则按0(中性)处理。
        try {
            List<FundingRateData> rates = dataFetchService.getRecentFundingRates(symbol, 1);
            if (rates != null && !rates.isEmpty()) {
                // getFundingRates 按 funding_time DESC 返回, index 0 为最新
                return Double.parseDouble(rates.get(0).getFundingRate());
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    // ---- helpers ----

    private static double pctChange(double from, double to) {
        if (from <= 0) return 0;
        return (to - from) / from * 100;
    }

    private static double priceAgo(List<KlineData> k, int barsBack) {
        int idx = k.size() - 1 - barsBack;
        if (idx < 0) idx = 0;
        return k.get(idx).getClosePrice();
    }

    private static double oiVal(List<OpenInterestData> oi, int idx) {
        return oi.get(idx).getOpenInterestValue();
    }

    private static double oiAgo(List<OpenInterestData> oi, int barsBack) {
        int idx = oi.size() - 1 - barsBack;
        if (idx < 0) idx = 0;
        return oi.get(idx).getOpenInterestValue();
    }

    private static double sumQuoteVol(List<KlineData> k, int from, int to) {
        if (from < 0) from = 0;
        double sum = 0;
        for (int i = from; i < to && i < k.size(); i++) {
            sum += k.get(i).getQuoteVolume();
        }
        return sum;
    }

    /** 当前价在给定K线窗口[最低,最高]中的分位 (0=最低,100=最高)。 */
    private static double percentileInWindow(List<KlineData> k, double price) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (KlineData kd : k) {
            lo = Math.min(lo, kd.getLowPrice());
            hi = Math.max(hi, kd.getHighPrice());
        }
        if (hi <= lo) return 50;
        return (price - lo) / (hi - lo) * 100;
    }
}
