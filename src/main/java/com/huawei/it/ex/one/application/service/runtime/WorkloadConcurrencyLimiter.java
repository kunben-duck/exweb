/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.application.config.ResourceIsolationProperties;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;

/**
 * 外部慢资源并发隔离器。
 *
 * <p>该类使用当前 JVM 内的轻量 semaphore 保护 Relay Runtime、DomainAgent 与对象存储调用。
 * 它不是业务事实源，也不承担集群级限流；集群总容量应由网关限流和下游服务配额共同控制。</p>
 */
@Service
public class WorkloadConcurrencyLimiter {
    private final Semaphore agentRuntime;
    private final Semaphore domainAgent;
    private final Semaphore documentStorage;

    public WorkloadConcurrencyLimiter(ResourceIsolationProperties properties) {
        this.agentRuntime = new Semaphore(properties.normalizedAgentRuntimeMaxConcurrent());
        this.domainAgent = new Semaphore(properties.normalizedDomainAgentMaxConcurrent());
        this.documentStorage = new Semaphore(properties.normalizedDocumentStorageMaxConcurrent());
    }

    public <T> Flux<T> protectAgentRuntime(Flux<T> source) {
        return protectFlux("AGENT_RUNTIME_BUSY", agentRuntime, source);
    }

    public <T> Flux<T> protectDomainAgent(Flux<T> source) {
        return protectFlux("DOMAIN_AGENT_BUSY", domainAgent, source);
    }

    public <T> Mono<T> protectDocumentStorage(Mono<T> source) {
        return protectMono("DOCUMENT_STORAGE_BUSY", documentStorage, source);
    }

    /**
     * 在已经处于阻塞工作线程的代码中获取对象存储许可。
     *
     * @return 需要在 finally/try-with-resources 中释放的许可。
     */
    public Permit acquireDocumentStorage() {
        return acquire("DOCUMENT_STORAGE_BUSY", documentStorage);
    }

    private <T> Flux<T> protectFlux(String code, Semaphore semaphore, Flux<T> source) {
        return Flux.defer(() -> {
            if (!semaphore.tryAcquire()) {
                return Flux.error(new IllegalStateException(code + ": 当前外部服务并发已达上限，请稍后重试"));
            }
            return source.doFinally(signalType -> semaphore.release());
        });
    }

    private <T> Mono<T> protectMono(String code, Semaphore semaphore, Mono<T> source) {
        return Mono.defer(() -> {
            Permit permit = acquire(code, semaphore);
            return source.doFinally(signalType -> permit.close());
        });
    }

    private Permit acquire(String code, Semaphore semaphore) {
        if (!semaphore.tryAcquire()) {
            throw new IllegalStateException(code + ": 当前外部服务并发已达上限，请稍后重试");
        }
        return semaphore::release;
    }

    /**
     * bulkhead 许可句柄。
     */
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
