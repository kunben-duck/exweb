package com.huawei.it.ex.one.domain.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次请求或 RuntimeBinding 上的完整 Agent 模式快照。
 *
 * <p>空 selections 表示显式清除；null profile 由调用方解释为未提交新快照。</p>
 *
 * @param selections 按前端提交顺序保存的模式维度。
 */
public record AgentModeProfile(List<AgentModeSelection> selections) {
    public static final int MAX_SELECTIONS = 16;

    public AgentModeProfile {
        selections = selections == null ? List.of() : List.copyOf(selections);
        if (selections.size() > MAX_SELECTIONS) {
            throw new IllegalArgumentException("agentMode.selections 最多允许 " + MAX_SELECTIONS + " 项");
        }
        Set<String> schemes = new HashSet<>();
        for (AgentModeSelection selection : selections) {
            if (selection == null) {
                throw new IllegalArgumentException("agentMode.selections 不能包含 null");
            }
            if (!schemes.add(selection.scheme())) {
                throw new IllegalArgumentException("agentMode.selections 不允许重复 scheme: " + selection.scheme());
            }
        }
    }

    public static AgentModeProfile empty() {
        return new AgentModeProfile(List.of());
    }

    public boolean emptyProfile() {
        return selections.isEmpty();
    }
}
