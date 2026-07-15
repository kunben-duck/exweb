package com.huawei.it.ex.one.interfaces.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 前端显式选择 DomainAgent 时携带的展示用意图摘要。
 *
 * @param intentId 意图编码；前端没有该值时可为空。
 * @param intentName 意图展示名称。
 */
public record ChatSelectedIntentDto(
        @Size(max = 128, message = "selectedIntent.intentId 长度不能超过 128")
        String intentId,
        @NotBlank(message = "selectedIntent.intentName 不能为空")
        @Size(max = 256, message = "selectedIntent.intentName 长度不能超过 256")
        String intentName
) {}
