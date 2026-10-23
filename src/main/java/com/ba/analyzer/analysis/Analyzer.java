package com.ba.analyzer.analysis;

import com.ba.analyzer.model.AnalysisReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析器接口
 * 定义所有分析策略的统一规范，每个分析器必须有名称、描述和执行分析的方法
 * 支持两种调用方式：
 * - analyze(symbols): 使用配置文件中的默认参数
 * - analyze(symbols, params): 使用传入参数覆盖默认配置，未传入的参数仍使用配置文件值
 */
public interface Analyzer {

    String getName();

    String getDescription();

    AnalysisReport analyze(List<String> symbols);

    default AnalysisReport analyze(List<String> symbols, Map<String, Object> params) {
        return analyze(symbols);
    }

    default boolean isEnabled() {
        return true;
    }

    /**
     * 是否依赖日内高频数据。
     * 返回true的分析器在short-term批次(每5分钟)运行, 如多空比异动(1h LSR);
     * 返回false的在daily批次(每天)运行。
     */
    default boolean requiresIntradayData() {
        return false;
    }

    /**
     * 页面可调参数说明: key → {type, description, default}。默认值须取自配置, 使页面显示即生效值。
     * 返回 LinkedHashMap 以固定页面参数顺序。
     */
    default Map<String, Object> getParamSpec() {
        return new LinkedHashMap<>();
    }

    static Map<String, Object> param(String type, String description, Object defaultValue) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        p.put("default", defaultValue);
        return p;
    }

    default int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        if (params == null || !params.containsKey(key)) return defaultValue;
        Object val = params.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    default double getDoubleParam(Map<String, Object> params, String key, double defaultValue) {
        if (params == null || !params.containsKey(key)) return defaultValue;
        Object val = params.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
