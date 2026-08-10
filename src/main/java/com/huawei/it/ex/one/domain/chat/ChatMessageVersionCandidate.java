package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;

/**
 * 历史消息当前页所需的轻量版本候选。
 *
 * <p>该模型只承载版本切换展示字段；正文、metadata、附件和 parts 不参与查询。</p>
 *
 * @param pageMessageId 当前页中需要装配版本信息的消息标识。
 * @param messageId sibling 候选消息标识。
 * @param role 候选消息角色。
 * @param siblingIndex 候选在同父同角色分组中的稳定序号。
 * @param locked 候选是否锁定。
 * @param originType 候选来源类型。
 * @param editedFromMessageId 编辑来源消息标识。
 * @param regeneratedFromMessageId 重新生成来源消息标识。
 * @param createdAt 候选创建时间。
 * @param switchLeafMessageId 选择该候选后应切换到的叶子消息标识。
 */
public record ChatMessageVersionCandidate(
        String pageMessageId,
        String messageId,
        String role,
        int siblingIndex,
        boolean locked,
        String originType,
        String editedFromMessageId,
        String regeneratedFromMessageId,
        Instant createdAt,
        String switchLeafMessageId
) {
}
