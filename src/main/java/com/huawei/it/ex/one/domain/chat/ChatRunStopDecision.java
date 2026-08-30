/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

/**
 * stop 请求被 run 生命周期服务接收后的内部决策。
 *
 * @param run 当前 run 快照。
 * @param appendCancelledEvent 是否需要追加 run.cancelled 终态事件。
 */
public record ChatRunStopDecision(
        ChatRun run,
        boolean appendCancelledEvent
) {}
