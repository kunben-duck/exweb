package com.huawei.it.ex.one.interfaces.chat.dto;

/** 前端可见的Intent候选技能。 */
public record IntentCandidateDto(
        String intentId,
        String accessName,
        String skillId,
        String intentName,
        Double confidence
) {
}
