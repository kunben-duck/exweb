package com.huawei.it.ex.one.application.integration.agent;

/**
 * AgentRuntime 会话使用模式。
 *
 * <p>该字段只表达 ChatService 与下游 Runtime 的会话协议语义，不暴露给前端。Relay WebSocket
 * 需要明确区分首次 {@code NEW} 与后续 {@code RESUME}，不能仅通过 runtimeSessionId 是否为空推断。</p>
 */
public enum RuntimeSessionMode {
    NEW,
    RESUME
}
