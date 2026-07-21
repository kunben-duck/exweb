package com.huawei.it.ex.one.runtime.application.model;

import com.huawei.it.ex.one.runtime.application.model.RuntimeSessionMode;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;

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
