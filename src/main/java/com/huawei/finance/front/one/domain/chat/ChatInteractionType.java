package com.huawei.finance.front.one.domain.chat;

/**
 * 等待用户输入类型。
 */
public enum ChatInteractionType {
    /** 旧澄清类型兼容值；新数据使用 AGENT_CLARIFICATION。 */
    CLARIFICATION,
    /** Runtime/Agent 执行中发起的对话澄清。 */
    AGENT_CLARIFICATION,
    /** 路由阶段意图服务发起的多轮澄清。 */
    INTENT_CLARIFICATION,
    /** 审批确认，首版暂不启用。 */
    APPROVAL,
    /** 普通确认，首版暂不启用。 */
    CONFIRMATION,
    /** 受保护的 DomainAgent 拒答后，确认是否切换到新的 DomainAgent 或 Relay。 */
    ROUTE_SWITCH_CONFIRMATION
}
