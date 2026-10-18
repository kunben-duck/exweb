/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.observation;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

/** 两级首帧独立判定；实现不得改变或消费业务事件。 */
public interface FirstBusinessFrameClassifier {
    Classification classify(ChatEvent event);

    default Classification classifyIntentResult() { return new Classification(true, false); }

    record Classification(boolean observation, boolean turn) {
        public static final Classification NONE = new Classification(false, false);
        public static final Classification BUSINESS = new Classification(true, true);
    }
}
