/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.domain.runtime.RuntimeProfileMetadata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 正常完成后的自动续接策略；问卷等待、失败及 Stop 不使用此策略。 */
@Component
public class RuntimeBindingCompletionPolicy {
    private final Set<String> nonStickyRoleNames;

    public RuntimeBindingCompletionPolicy(
            @Value("${financeex.agent-runtime.relay.domain-expert.non-sticky-role-names:}") String roleNames) {
        nonStickyRoleNames = Arrays.stream((roleNames == null ? "" : roleNames).split(","))
                .map(String::trim)
                .filter(roleName -> !roleName.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean retainBindingOnCompletion(RuntimeBinding binding) {
        if (binding == null) {
            return false;
        }
        if ("domain-agent".equals(binding.provider())) {
            return true;
        }
        if (!"relay".equals(binding.provider()) || !RuntimeProfileMetadata.isDomainExpert(binding.metadata())) {
            return false;
        }
        // 新的转换规则仅适用于 ACTIVE；其他状态保留原完成分支的处理。
        if (binding.status() != RuntimeBindingStatus.ACTIVE) {
            return true;
        }
        // 只比较服务端已保存的最终角色；不改写档案，后续仍按原条件恢复同一下游会话。
        String roleName = String.valueOf(binding.metadata().get(RuntimeProfileMetadata.ROLE_NAME_KEY)).trim();
        return !nonStickyRoleNames.contains(roleName);
    }
}
