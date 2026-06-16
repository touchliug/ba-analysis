package com.ba.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * 币安数据分析服务启动类 (ba-analysis)。
 *
 * 职责: 只读MySQL中的K线/持仓量/资金费率, 运行各类分析器并对外提供查询接口。
 * 与同步服务(ba-sync)完全独立, 仅通过MySQL交换数据 —— 本进程从不访问币安API。
 */
@SpringBootApplication
@EnableScheduling
public class AnalysisApplication {

    public static void main(String[] args) {
        // JVM 固定 Asia/Shanghai: 行为与北京开发环境一致, 不随部署服务器时区漂移。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(AnalysisApplication.class, args);
    }
}
