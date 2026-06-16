package com.ba.analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 合约列表服务 (ba-analysis 专用) —— 从MySQL推导币种全集, 不访问币安、不读本地文件。
 *
 * 币种全集 = 同步服务(ba-sync)已写入日K线的所有 symbol。
 * 同步服务写了哪些币, 分析服务就分析哪些币, 天然与同步侧解耦, 无需共享 symbols.json。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SymbolService {

    private final JdbcTemplate jdbc;

    public List<String> getSymbolNames() {
        List<String> symbols = jdbc.queryForList(
                "SELECT DISTINCT symbol FROM klines WHERE `interval`='1d' ORDER BY symbol",
                String.class);
        if (symbols.isEmpty()) {
            log.warn("No symbols found in klines table; sync service (ba-sync) may not have run yet");
        }
        return symbols;
    }
}
