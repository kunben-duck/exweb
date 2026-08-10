package com.huawei.it.ex.one.application.service.runtime;

/** Runtime流超过本机硬边界时使用的稳定内部异常。 */
public class RuntimeStreamLimitExceededException extends RuntimeException {
    public static final String CODE = "RUNTIME_STREAM_LIMIT_EXCEEDED";

    private final RuntimeStreamLimitType limitType;

    public RuntimeStreamLimitExceededException(RuntimeStreamLimitType limitType, String message) {
        super(message);
        this.limitType = limitType;
    }

    public RuntimeStreamLimitType limitType() {
        return limitType;
    }
}
