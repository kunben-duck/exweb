package com.huawei.it.ex.one.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.it.ex.one.application.config.RegionalAccessProperties;
import com.huawei.it.ex.one.application.integration.auth.AuthHeaderRequest;
import com.huawei.it.ex.one.application.integration.security.IpLocationProvider;
import com.huawei.it.ex.one.application.integration.security.RegionalLocationResult;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * SGOV-authenticated adapter for the trusted client-IP location endpoint.
 */
@Component
public class SgovIpLocationProvider implements IpLocationProvider {
    private static final AppLogger log = AppLoggerFactory.getLogger(SgovIpLocationProvider.class);

    private final WebClient webClient;
    private final RegionalAccessProperties properties;
    private final AuthHeaderProviderRegistry authHeaders;

    public SgovIpLocationProvider(WebClient.Builder webClientBuilder, RegionalAccessProperties properties,
                                  AuthHeaderProviderRegistry authHeaders) {
        this.webClient = webClientBuilder.build();
        this.properties = properties;
        this.authHeaders = authHeaders;
    }

    @Override
    public Mono<RegionalLocationResult> findCountry(UserContext user, String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return Mono.just(RegionalLocationResult.notApplicable());
        }
        String baseUrl = properties.normalizedIpBaseUrl();
        if (baseUrl.isEmpty()) {
            return Mono.just(RegionalLocationResult.unavailable());
        }
        return Mono.defer(() -> {
            URI endpoint = UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/f/idata/common/getAddressByIP")
                    .queryParam("ip", ipAddress)
                    .queryParam("full", 1)
                    .build()
                    .encode()
                    .toUri();
            return webClient.get()
                    .uri(endpoint)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> authHeaders.headers(authRequest(user, endpoint)).forEach(headers::set))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .map(this::mapResponse)
                    .switchIfEmpty(Mono.just(RegionalLocationResult.unavailable()));
        }).onErrorResume(error -> {
            log.warn("Regional IP location request failed; failing open, exceptionClass={}",
                    error.getClass().getName());
            return Mono.just(RegionalLocationResult.unavailable());
        });
    }

    private RegionalLocationResult mapResponse(JsonNode root) {
        if (!successful(root)) {
            return RegionalLocationResult.unavailable();
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return RegionalLocationResult.unavailable();
        }
        return RegionalLocationResult.found(data.path(0).path("country").asText(null));
    }

    private boolean successful(JsonNode root) {
        JsonNode code = root == null ? null : root.get("code");
        return code != null && code.canConvertToInt() && code.intValue() == 200;
    }

    private AuthHeaderRequest authRequest(UserContext user, URI endpoint) {
        return new AuthHeaderRequest(
                user == null ? null : user.tenantId(),
                user == null ? null : user.ownerUserId(),
                "regional-ip-location",
                "query",
                properties.normalizedIpBaseUrl(),
                endpoint.getPath(),
                null
        );
    }
}
