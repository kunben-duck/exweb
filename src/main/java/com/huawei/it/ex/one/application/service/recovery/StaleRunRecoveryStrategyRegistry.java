package com.huawei.it.ex.one.application.service.recovery;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * stale run 恢复策略注册表。
 */
@Service
public class StaleRunRecoveryStrategyRegistry {
    private final Map<String, StaleRunRecoveryStrategy> strategies;

    public StaleRunRecoveryStrategyRegistry(List<StaleRunRecoveryStrategy> strategies) {
        Map<String, StaleRunRecoveryStrategy> indexed = new LinkedHashMap<>();
        for (StaleRunRecoveryStrategy strategy : strategies) {
            indexed.put(normalize(strategy.strategyName()), strategy);
        }
        this.strategies = Map.copyOf(indexed);
    }

    /**
     * 根据策略名查找恢复策略。
     *
     * @param strategyName 策略名。
     * @return 策略实现；不存在时为空。
     */
    public StaleRunRecoveryStrategy find(String strategyName) {
        return strategies.get(normalize(strategyName));
    }

    private String normalize(String strategyName) {
        return strategyName == null ? "" : strategyName.trim().toUpperCase(Locale.ROOT);
    }
}
