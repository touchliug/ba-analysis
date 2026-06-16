package com.ba.analyzer.analysis;

import com.ba.analyzer.model.AnalysisReport;

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
     * 是否依赖日内(5m级)数据。
     * 返回true的分析器在short-term批次(每5分钟,已同步5m OI)运行;
     * 返回false的在daily批次(每天,预加载日线)运行。
     */
    default boolean requiresIntradayData() {
        return false;
    }

    /**
     * 该分析器需要的日线历史天数(用于daily批次预加载)。
     * 默认0; AbstractKlineAnalyzer会从默认参数的"days"推导, 无"days"的分析器自行覆盖。
     */
    default int requiredDays() {
        return 0;
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
