/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

/**
 * 分享链接当前不可访问的业务异常。
 *
 * <p>用于把已撤销、已过期等分享生命周期状态映射为稳定错误码。</p>
 */
public class ChatShareUnavailableException extends IllegalStateException {
    private final String code;

    public ChatShareUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
