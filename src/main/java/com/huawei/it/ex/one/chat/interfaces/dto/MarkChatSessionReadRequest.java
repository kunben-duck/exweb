package com.huawei.it.ex.one.chat.interfaces.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 会话已读水位提交请求。
 *
 * @param readThroughSeq 前端已经实际展示到的服务端消息 sequence。
 */
public record MarkChatSessionReadRequest(
        @NotNull(message = "readThroughSeq 不能为空")
        @PositiveOrZero(message = "readThroughSeq 不能小于 0")
        Long readThroughSeq
) {
}
