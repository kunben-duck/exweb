package com.huawei.finance.front.one.application.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 运行治理定时任务配置。
 *
 * <p>当前用于清理 MVC WebSocket 空闲连接和 run 准入控制的过期本机窗口；
 * 后续可在这里承载本机运行态指标刷新等轻量任务。</p>
 */
@Configuration
@EnableScheduling
public class OperationalSchedulingConfig {
}
