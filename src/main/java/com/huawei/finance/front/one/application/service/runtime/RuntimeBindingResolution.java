package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;

/**
 * 本轮 RuntimeBinding 解析结果。
 *
 * @param binding 本轮应使用的 RuntimeBinding。
 * @param sessionMode 下游 Runtime 会话协议模式。
 */
public record RuntimeBindingResolution(
        RuntimeBinding binding,
        RuntimeSessionMode sessionMode
) {
}
