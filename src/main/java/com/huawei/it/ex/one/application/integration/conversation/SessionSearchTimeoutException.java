package com.huawei.it.ex.one.application.integration.conversation;

/** 会话关键字搜索超过数据库查询预算。 */
public class SessionSearchTimeoutException extends RuntimeException {
    public SessionSearchTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
