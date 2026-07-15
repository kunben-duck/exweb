package com.huawei.it.ex.one.application.integration.share;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分享 provider 调用结果。
 *
 * @param success provider 是否确认发送成功。
 * @param errorCode 失败错误码；成功时为空。
 * @param errorMessage 失败错误信息；成功时为空。
 * @param providerResponse provider 安全响应摘要，不包含 Cookie、Authorization 或其他鉴权头。
 */
public record ChatShareProviderDeliveryResult(
        boolean success,
        String errorCode,
        String errorMessage,
        Map<String, Object> providerResponse
) {
    public ChatShareProviderDeliveryResult {
        providerResponse = immutableNullableMap(providerResponse);
    }

    public static ChatShareProviderDeliveryResult success(Map<String, Object> providerResponse) {
        return new ChatShareProviderDeliveryResult(true, null, null, providerResponse);
    }

    public static ChatShareProviderDeliveryResult failed(String errorCode, String errorMessage,
                                                         Map<String, Object> providerResponse) {
        return new ChatShareProviderDeliveryResult(false, errorCode, errorMessage, providerResponse);
    }

    private static Map<String, Object> immutableNullableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
