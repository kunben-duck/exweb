/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.document.dto;

/**
 * 前端文档处理状态 DTO。
 *
 * @param documentId 文档标识。
 * @param status 文档状态，例如 AVAILABLE、FAILED、DELETED。
 * @param tokenSize 文档解析后的 token 数量，可为空表示尚未解析。
 */
public record DocumentStatusDto(
        String documentId,
        String status,
        Long tokenSize
) {}
