package com.huawei.finance.front.one.interfaces.chat.dto;

import java.util.Map;

public record FrontChatEventDto(String runId, String sessionId, long sequence, String type, String messageType, Map<String, Object> payload) {}
