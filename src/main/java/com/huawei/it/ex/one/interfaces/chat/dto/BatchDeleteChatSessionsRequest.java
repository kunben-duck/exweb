package com.huawei.it.ex.one.interfaces.chat.dto;

import java.util.List;

/**
 * 批量软删除会话请求 DTO。
 *
 * @param sessionIds 需要删除的会话 ID 列表；服务端会去重、校验归属，并拒绝仍有 active run 的会话。
 */
public record BatchDeleteChatSessionsRequest(
        List<String> sessionIds
) {}
