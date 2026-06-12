package com.huawei.finance.front.one.interfaces.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个 conversation turn 流式片段。
 *
 * <p>{@code stream-item} 承载真实聊天事件，{@code heartbeat} 表示 turn 仍然活跃但暂无业务事件，
 * {@code done} 表示本轮 turn 的实时输出已经闭合。heartbeat/done 不写入事件事实源，也不推进
 * 前端 afterSeq 游标。</p>
 *
 * @param type turn stream 片段类型：stream-item、heartbeat、done。
 * @param conversationId 对外协议中的 conversation 标识，当前等于 ChatService sessionId。
 * @param turnId 对外协议中的 turn 标识，当前等于 ChatService runId。
 * @param streamItemId stream-item 的稳定标识，首版使用事件 seq 派生。
 * @param serverTimestampMs 服务端生成该片段的毫秒时间戳。
 * @param encodedItem 真实聊天事件，仅 stream-item 存在。
 * @param lastSeq heartbeat/done 看到的最近事件 seq。
 * @param terminalEventType done 对应的终态事件类型，例如 run.completed。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationTurnStreamPayloadDto(
        String type,
        String conversationId,
        String turnId,
        String streamItemId,
        Long serverTimestampMs,
        EncodedChatEventItemDto encodedItem,
        Long lastSeq,
        String terminalEventType
) {
}
