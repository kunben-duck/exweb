package com.huawei.it.ex.one.application.integration.conversation;

import reactor.core.publisher.Flux;

import java.util.function.Consumer;

/** 跨实例定向通知 ChatRun execution owner 执行用户 stop 的控制面端口。 */
public interface RunStopControlBus {

    /** 发布请求并返回订阅者数量及当前请求的响应流。 */
    Delivery send(Request request);

    /** 注册当前实例唯一的 stop 请求处理器。 */
    void registerHandler(Consumer<Request> handler);

    /** 将 owner 处理结果发送回请求实例。 */
    void respond(Response response);

    record Request(
            String requestId,
            String runId,
            String requesterInstanceId,
            String ownerInstanceId,
            long fencingToken,
            String reason
    ) {
    }

    record Response(
            String requestId,
            String runId,
            String requesterInstanceId,
            String ownerInstanceId,
            Status status,
            String runStatus,
            String message
    ) {
        public boolean terminal() {
            return status != Status.ACCEPTED;
        }
    }

    record Delivery(long subscriberCount, Flux<Response> responses) {
    }

    enum Status {
        ACCEPTED,
        COMMITTED,
        NOT_OWNER,
        UNAVAILABLE,
        FAILED
    }
}
