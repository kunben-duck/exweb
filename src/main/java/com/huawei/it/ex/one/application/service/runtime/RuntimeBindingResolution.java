package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

/**
 * 本轮 RuntimeBinding 解析结果。
 *
 * @param binding 本轮应使用的 RuntimeBinding。
 * @param sessionMode 下游 Runtime 会话协议模式。
 * @param previousBinding 本轮激活前的 Binding 快照；新建 Binding 时为空。
 */
public record RuntimeBindingResolution(
        RuntimeBinding binding,
        RuntimeSessionMode sessionMode,
        RuntimeBinding previousBinding
) {
    public RuntimeBindingResolution(RuntimeBinding binding, RuntimeSessionMode sessionMode) {
        this(binding, sessionMode, null);
    }
}
