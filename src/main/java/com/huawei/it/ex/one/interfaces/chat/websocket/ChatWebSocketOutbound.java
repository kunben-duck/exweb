/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.websocket;

import com.huawei.it.ex.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;

/**
 * WebSocket 出站通道抽象。
 *
 * <p>前端 WebSocket 在正式部署中可能运行在 WebFlux 或 Servlet/MVC 两种服务端栈上。
 * 协议层只关心“发送一个 envelope”，不关心底层是 Reactor sink 还是 Servlet session，
 * 从而保证两种启动模式复用同一套 connect/subscribe 协议实现。</p>
 */
@FunctionalInterface
public interface ChatWebSocketOutbound {
    /**
     * 向当前物理连接发送一个 WebSocket envelope。
     *
     * @param envelope 前端 WebSocket 协议 envelope。
     */
    void emit(ChatWebSocketEnvelopeDto envelope);
}
