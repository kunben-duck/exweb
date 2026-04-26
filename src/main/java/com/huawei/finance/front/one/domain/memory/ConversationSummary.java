package com.huawei.finance.front.one.domain.memory;

import java.time.Instant;

public record ConversationSummary(String id, String sessionId, String summaryText, Long messageFromSeq, Long messageToSeq, Instant createdAt) {}
