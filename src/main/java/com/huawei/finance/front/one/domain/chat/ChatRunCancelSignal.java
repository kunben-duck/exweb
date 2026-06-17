package com.huawei.finance.front.one.domain.chat;

/**
 * Redis cancel flag 的读取结果。
 */
public enum ChatRunCancelSignal {
    /** Redis 明确存在取消标记。 */
    REQUESTED,
    /** Redis 明确不存在取消标记。 */
    NOT_REQUESTED,
    /** Redis 暂不可用或读取失败，需要回源数据库。 */
    UNKNOWN
}
