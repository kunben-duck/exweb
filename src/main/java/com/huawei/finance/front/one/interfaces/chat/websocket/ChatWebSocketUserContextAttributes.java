package com.huawei.finance.front.one.interfaces.chat.websocket;

import com.huawei.finance.front.one.domain.auth.UserContext;
import java.util.Map;

/**
 * WebSocket 握手阶段固化的用户身份属性。
 *
 * <p>MVC/Servlet WebSocket 完成 HTTP upgrade 后，连接生命周期已经脱离普通 Controller
 * 请求线程，企业权限 ThreadLocal 可能不可用。因此必须在 handshake 阶段把 {@link UserContext}
 * 写入 WebSocket attributes，后续连接建立和订阅都只读取该不可变快照。</p>
 */
final class ChatWebSocketUserContextAttributes {
    /** Servlet WebSocket session attributes 中保存 UserContext 的固定 key。 */
    static final String USER_CONTEXT_ATTRIBUTE = "FIN_EX_WS_USER_CONTEXT";

    private ChatWebSocketUserContextAttributes() {
    }

    /**
     * 写入握手阶段解析出的用户身份。
     *
     * @param attributes WebSocket session attributes。
     * @param user 已校验的用户身份快照。
     */
    static void put(Map<String, Object> attributes, UserContext user) {
        if (attributes == null) {
            throw new IllegalArgumentException("WebSocket attributes 不能为空");
        }
        if (user == null) {
            throw new SecurityException("WebSocket 用户身份缺失");
        }
        attributes.put(USER_CONTEXT_ATTRIBUTE, user);
    }

    /**
     * 读取握手阶段固化的用户身份。
     *
     * @param attributes WebSocket session attributes。
     * @return 用户身份快照。
     */
    static UserContext require(Map<String, Object> attributes) {
        Object value = attributes == null ? null : attributes.get(USER_CONTEXT_ATTRIBUTE);
        if (value instanceof UserContext user) {
            return user;
        }
        throw new SecurityException("WebSocket 用户身份未在握手阶段固化");
    }
}
