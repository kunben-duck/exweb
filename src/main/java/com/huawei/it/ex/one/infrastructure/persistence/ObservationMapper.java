/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import com.huawei.it.ex.one.domain.observation.AgentObservationSnapshot;
import com.huawei.it.ex.one.domain.observation.TurnTraceSnapshot;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ObservationMapper {
    /**
     * 合并问答阶段与最早首帧、拒答事实，拒绝迟到快照回退状态。
     * @param snapshot 可信问答快照
     */
    void saveTrace(TurnTraceSnapshot snapshot);
    /**
     * 保存单次调用快照，已结束的记录不再被开始快照覆盖。
     * @param snapshot 可信调用快照
     */
    void saveObservation(AgentObservationSnapshot snapshot);
}
