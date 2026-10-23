package com.ba.analyzer.analysis;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.AnalysisReport;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.service.DataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * N日区间 —— 合并原"N日高低点"与"N日最低"。
 *
 * 截至最近收盘的 N 日最高/最低价、现价在区间中的位置(0=最低, 100=最高),
 * 并在最低价或现价上标记豹子号/整数关口。可按位置范围筛选(如只看贴近低点的币)。
 */
@Slf4j
@Component
public class NDayRangeAnalyzer extends AbstractKlineAnalyzer {

    /** 整数关口容差: 价格与所在数量级整数位相差 < 0.2%。 */
    static final double ROUND_LEVEL_TOLERANCE = 0.002;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM-dd");

    public NDayRangeAnalyzer(DataFetchService dataFetchService, AppProperties appProperties) {
        super(dataFetchService, appProperties);
    }

    @Override
    public String getName() {
        return "N日区间";
    }

    @Override
    public String getDescription() {
        return buildDescription(getDefaultParams());
    }

    @Override
    public boolean isEnabled() {
        return appProperties.getAnalysis().getNDayRange().isEnabled();
    }

    @Override
    protected Map<String, Object> getDefaultParams() {
        var c = appProperties.getAnalysis().getNDayRange();
        return createParams("days", c.getDays(),
                "minPositionPercent", c.getMinPositionPercent(),
                "maxPositionPercent", c.getMaxPositionPercent(),
                "onlyTagged", c.isOnlyTagged());
    }

    @Override
    protected Map<String, Object> resolveParams(Map<String, Object> requestParams) {
        var c = appProperties.getAnalysis().getNDayRange();
        return createParams("days", getIntParam(requestParams, "days", c.getDays()),
                "minPositionPercent", getDoubleParam(requestParams, "minPositionPercent", c.getMinPositionPercent()),
                "maxPositionPercent", getDoubleParam(requestParams, "maxPositionPercent", c.getMaxPositionPercent()),
                "onlyTagged", getBoolParam(requestParams, "onlyTagged", c.isOnlyTagged()));
    }

    @Override
    public Map<String, Object> getParamSpec() {
        var c = appProperties.getAnalysis().getNDayRange();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("days", Analyzer.param("int", "统计天数(截至最近收盘)", c.getDays()));
        s.put("minPositionPercent", Analyzer.param("double", "现价区间位置下限%(0=最低点)", c.getMinPositionPercent()));
        s.put("maxPositionPercent", Analyzer.param("double", "现价区间位置上限%(100=最高点; 设20即只看贴近低点)", c.getMaxPositionPercent()));
        s.put("onlyTagged", Analyzer.param("boolean", "只看带豹子号/整数关口标记的币(true/false)", c.isOnlyTagged()));
        return s;
    }

    @Override
    protected Map<String, List<KlineData>> fetchData(List<String> symbols, Map<String, Object> params) {
        return dataFetchService.fetchDailyKlines(symbols, (int) params.get("days") + 1);
    }

    @Override
    protected String buildDescription(Map<String, Object> params) {
        return String.format("截至最近收盘%d日的最高/最低价与现价位置(%.0f%%-%.0f%%), 标记豹子号和整数关口",
                (int) params.get("days"), (double) params.get("minPositionPercent"),
                (double) params.get("maxPositionPercent"));
    }

    /** 带标记的排前, 其次按区间位置升序(越贴近低点越靠前)。 */
    @Override
    protected void sortResults(List<AnalysisReport.CoinAnalysis> matched) {
        matched.sort(Comparator
                .comparing((AnalysisReport.CoinAnalysis c) -> c.getDetail().startsWith("[") ? 0 : 1)
                .thenComparingDouble(AnalysisReport.CoinAnalysis::getChangePercent));
    }

    @Override
    protected List<AnalysisReport.CoinAnalysis> doAnalyze(
            Map<String, List<KlineData>> klineMap, Map<String, Object> params) {
        int days = (int) params.get("days");
        double minPos = (double) params.get("minPositionPercent");
        double maxPos = (double) params.get("maxPositionPercent");
        boolean onlyTagged = (boolean) params.get("onlyTagged");
        List<AnalysisReport.CoinAnalysis> matched = new ArrayList<>();

        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            List<KlineData> klines = entry.getValue();
            List<KlineData> recent = klines.size() > days ? klines.subList(klines.size() - days, klines.size()) : klines;
            if (recent.isEmpty()) continue;

            KlineData lowBar = recent.get(0);
            KlineData highBar = recent.get(0);
            for (KlineData k : recent) {
                if (k.getLowPrice() < lowBar.getLowPrice()) lowBar = k;
                if (k.getHighPrice() > highBar.getHighPrice()) highBar = k;
            }
            double low = lowBar.getLowPrice();
            double high = highBar.getHighPrice();
            double last = recent.get(recent.size() - 1).getClosePrice();
            if (low <= 0 || last <= 0) continue;

            double position = high > low ? (last - low) / (high - low) * 100 : 50;
            if (position < minPos || position > maxPos) continue;

            boolean leopard = isLeopardNumber(formatPrice(low)) || isLeopardNumber(formatPrice(last));
            boolean roundLevel = isRoundLevel(low) || isRoundLevel(last);
            if (onlyTagged && !leopard && !roundLevel) continue;

            String tags = (leopard ? "[豹子号]" : "") + (roundLevel ? "[整数关口]" : "");
            matched.add(AnalysisReport.CoinAnalysis.builder()
                    .symbol(entry.getKey())
                    .currentPrice(last)
                    .changePercent(position)
                    .detail(String.format("%s%d日最低%s(%s) 最高%s(%s) 现价%s 位置%.1f%% 距低点%+.1f%%",
                            tags.isEmpty() ? "" : tags + " ", days,
                            plain(low), date(lowBar), plain(high), date(highBar), plain(last),
                            position, (last - low) / low * 100))
                    .build());
        }
        return matched;
    }

    private static String date(KlineData k) {
        return Instant.ofEpochMilli(k.getOpenTime()).atOffset(ZoneOffset.ofHours(8)).format(DATE_FMT);
    }

    private static String plain(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /**
     * 整数关口: 价格贴近其数量级的整数位(容差0.2%)。
     * 数量级 m=10^floor(log10 p), 关口为 m 的整数倍: 0.1/0.2…(p∈[0.1,1)), 1/2…, 10000/20000…。
     * (原实现按"距最近整数0.05%"判断, 对价格≥1000的币永远成立, 对<1的币又识别不到0.1类关口。)
     */
    static boolean isRoundLevel(double price) {
        if (price <= 0) return false;
        double magnitude = Math.pow(10, Math.floor(Math.log10(price)));
        double level = Math.round(price / magnitude) * magnitude;
        return Math.abs(price - level) / price < ROUND_LEVEL_TOLERANCE;
    }

    /** 豹子号: 小数部分去掉前导0后有4个以上连续相同的非零数字(如 0.1111、0.033333)。 */
    static boolean isLeopardNumber(String priceStr) {
        int dotIdx = priceStr.indexOf('.');
        if (dotIdx < 0) return false;
        String stripped = priceStr.substring(dotIdx + 1).replaceFirst("^0+", "");
        if (stripped.length() < 4) return false;
        int run = 1;
        for (int i = 1; i < stripped.length(); i++) {
            if (stripped.charAt(i) == stripped.charAt(i - 1) && stripped.charAt(i) != '0') {
                if (++run >= 4) return true;
            } else {
                run = 1;
            }
        }
        return false;
    }

    static String formatPrice(double price) {
        return String.format("%.8f", price);
    }
}
