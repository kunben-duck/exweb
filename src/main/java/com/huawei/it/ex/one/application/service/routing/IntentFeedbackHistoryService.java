/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.routing;

import com.huawei.it.ex.one.application.integration.intent.IntentFeedbackRepository;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.intent.IntentFeedback;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One bounded transaction for the entire history enrichment, including all batches. */
@Service
public class IntentFeedbackHistoryService {
    private final IntentFeedbackRepository repository;

    public IntentFeedbackHistoryService(IntentFeedbackRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, timeout = 2)
    public Map<String, IntentFeedback> find(UserContext user, String sessionId, Collection<String> ids) {
        List<String> runIds = ids.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        Map<String, IntentFeedback> result = new LinkedHashMap<>();
        for (int offset = 0; offset < runIds.size(); offset += 200) {
            for (IntentFeedback feedback : repository.findByOwnerAndRuns(user.tenantId(), user.ownerUserId(),
                    runIds.subList(offset, Math.min(offset + 200, runIds.size())))) {
                if (sessionId.equals(feedback.sessionId())) {
                    result.put(feedback.runId(), feedback);
                }
            }
        }
        return Map.copyOf(result);
    }
}
