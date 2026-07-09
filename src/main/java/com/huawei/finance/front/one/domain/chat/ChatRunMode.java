package com.huawei.finance.front.one.domain.chat;

/**
 * 单轮 run 的消息树写入模式。
 *
 * <p>该枚举只描述 ChatService 可见消息树如何变化，不描述底层 Runtime 的协议。
 * Runtime 仍然只接收一次标准 query；消息树语义由 ChatService 在调用 Runtime 前后落库。</p>
 */
public enum ChatRunMode {
    /** 普通继续提问，在当前 active path 后新增一条 user 消息和一条 assistant 回复。 */
    NEXT,
    /** 编辑历史 user 消息，创建同父节点的新 user sibling，再生成新的 assistant 回复。 */
    EDIT_USER,
    /** 针对已有 assistant 回复重新生成，创建同父节点的新 assistant sibling。 */
    REGENERATE_ASSISTANT,
    /** 续接等待用户输入的 Interaction，不创建新的普通 user 消息。 */
    CONTINUE_INTERACTION;

    /**
     * 将前端字符串转换为安全枚举值。
     *
     * @param value 前端传入的 runMode，可为空。
     * @return 解析后的模式；为空或非法时默认普通继续提问。
     */
    public static ChatRunMode from(String value) {
        if (value == null || value.isBlank()) {
            return NEXT;
        }
        try {
            return ChatRunMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return NEXT;
        }
    }
}
