package com.ba.analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 合约列表服务 (ba-analysis 专用) —— 从MySQL推导币种全集, 不访问币安、不读本地文件。
 *
 * 主源: symbols 表中 status='TRADING' 且 quote_asset='USDT' 的合约 (规范来源)。
 * 兜底: symbols 表为空时回退到 klines 去重 (同步服务尚未灌 symbols 时不致全停)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SymbolService {

    private final JdbcDataStore dataStore;

    public List<String> getSymbolNames() {
        List<String> symbols = dataStore.getTradingSymbolNames();
        if (!symbols.isEmpty()) return symbols;

        log.warn("symbols table empty; falling back to DISTINCT symbol FROM klines");
        List<String> fromKlines = dataStore.getSymbolNamesFromKlines();
        if (fromKlines.isEmpty()) {
            log.warn("No symbols in klines either; sync service (ba-sync) may not have run yet");
        }
        return fromKlines;
    }
}
