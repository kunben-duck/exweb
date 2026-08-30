/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.storage.api;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

final class CapturingClientHttpRequest implements ClientHttpRequest {
    private final URI uri;
    private final HttpHeaders headers = new HttpHeaders();
    private final MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    CapturingClientHttpRequest(URI uri) {
        this.uri = uri;
    }

    @Override
    public HttpMethod getMethod() {
        return HttpMethod.POST;
    }

    @Override
    public URI getURI() {
        return uri;
    }

    @Override
    public <T> T getNativeRequest() {
        return null;
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public MultiValueMap<String, HttpCookie> getCookies() {
        return cookies;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public DataBufferFactory bufferFactory() {
        return bufferFactory;
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        return Flux.from(body)
                .doOnNext(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    this.body.write(bytes, 0, bytes.length);
                })
                .then();
    }

    @Override
    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        return Flux.from(body).concatMap(this::writeWith).then();
    }

    @Override
    public void beforeCommit(Supplier<? extends Mono<Void>> action) {
    }

    @Override
    public boolean isCommitted() {
        return false;
    }

    @Override
    public Mono<Void> setComplete() {
        return Mono.empty();
    }

    String bodyAsString() {
        return body.toString(StandardCharsets.UTF_8);
    }
}
