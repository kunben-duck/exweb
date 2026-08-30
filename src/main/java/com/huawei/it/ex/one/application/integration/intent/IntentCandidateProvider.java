/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.intent;

import com.huawei.it.ex.one.domain.auth.UserContext;

import reactor.core.publisher.Mono;

import java.util.List;

/** 按ChatService user消息ID查询Intent候选技能的下游端口。 */
public interface IntentCandidateProvider {
    Mono<List<IntentCandidate>> findCandidates(UserContext user, String messageId);
}
