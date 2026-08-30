/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在短事务中提交自动标题，防止迟到响应覆盖人工标题或更新版本。 */
@Service
class SessionTitleCommitService {
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final SessionRepository sessionRepository;
    private final SessionTitleMetadata metadata;

    SessionTitleCommitService(SessionRepository sessionRepository, SessionTitleMetadata metadata) {
        this.sessionRepository = sessionRepository;
        this.metadata = metadata;
    }

    @Transactional(timeout = 2)
    boolean apply(SessionTitleCandidate candidate, String title) {
        sessionRepository.lockForMessageMutation(
                candidate.tenantId(), candidate.userId(), candidate.sessionId());
        ChatSession session = sessionRepository.findByTenantIdAndUserIdAndId(
                        candidate.tenantId(), candidate.userId(), candidate.sessionId())
                .orElse(null);
        if (session == null || !STATUS_ACTIVE.equals(session.status())) {
            return false;
        }
        SessionTitleSummaryState current = metadata.read(session.metadataJson()).orElse(null);
        if (current == null || !current.source().autoReplaceable()
                || !current.olderThan(candidate.queryCount(), candidate.nodeOrder())) {
            return false;
        }
        String nextMetadata = metadata.markAuto(
                session.metadataJson(), candidate.queryCount(), candidate.nodeOrder());
        sessionRepository.updateTitleWithoutTouch(session, title, nextMetadata);
        return true;
    }
}
