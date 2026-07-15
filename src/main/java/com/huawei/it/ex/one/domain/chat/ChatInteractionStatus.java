package com.huawei.it.ex.one.domain.chat;

/**
 * 等待用户输入请求的生命周期状态。
 */
public enum ChatInteractionStatus {
    /** 已向前端发出澄清/审批请求，等待用户提交。 */
    WAITING,
    /** 用户已提交，后端正在把结果续接到 Runtime。 */
    RESPONDING,
    /** Runtime 续接完成，本次等待已消费。 */
    ANSWERED,
    /** 会话删除、stop 或管理动作取消了等待请求。 */
    CANCELLED,
    /** 等待请求超过有效期。 */
    EXPIRED
}
