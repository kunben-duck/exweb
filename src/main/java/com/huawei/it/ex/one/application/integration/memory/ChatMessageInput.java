/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.memory;

/** Minimal trusted input projection; no metadata, Parts or Attachments. */
public record ChatMessageInput(String sessionId, String role, String content) {}
