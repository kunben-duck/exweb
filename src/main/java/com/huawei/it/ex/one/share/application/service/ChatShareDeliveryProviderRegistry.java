package com.huawei.it.ex.one.share.application.service;

import com.huawei.it.ex.one.share.application.client.ChatShareDeliveryProvider;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 分享发送 provider 注册表。
 */
@Component
public class ChatShareDeliveryProviderRegistry {
    private final Map<String, ChatShareDeliveryProvider> providers;

    public ChatShareDeliveryProviderRegistry(List<ChatShareDeliveryProvider> providers) {
        this.providers = providers == null ? Map.of() : providers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        provider -> normalize(provider.providerCode()),
                        Function.identity(),
                        (left, right) -> left
                ));
    }

    /**
     * 按 provider 编码查找发送实现。
     *
     * @param providerCode provider 编码。
     * @return provider 实现。
     */
    public ChatShareDeliveryProvider requiredProvider(String providerCode) {
        String code = normalize(providerCode);
        if (code.isBlank()) {
            throw new IllegalArgumentException("provider 不能为空");
        }
        ChatShareDeliveryProvider provider = providers.get(code);
        if (provider == null) {
            throw new IllegalArgumentException("未知分享发送 provider: " + providerCode);
        }
        return provider;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
