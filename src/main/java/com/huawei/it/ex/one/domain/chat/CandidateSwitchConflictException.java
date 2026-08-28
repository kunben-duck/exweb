package com.huawei.it.ex.one.domain.chat;

/** 候选DomainAgent切换在当前Run或消息路径状态下无法继续。 */
public final class CandidateSwitchConflictException extends IllegalStateException {
    public static final String STOP_PENDING = "CANDIDATE_SWITCH_STOP_PENDING";
    public static final String STALE_SOURCE = "CANDIDATE_SWITCH_STALE_SOURCE";

    private final String code;

    private CandidateSwitchConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static CandidateSwitchConflictException stopPending(String runId) {
        return new CandidateSwitchConflictException(
                STOP_PENDING,
                "原任务仍在停止中，请稍后重试: runId=" + runId);
    }

    public static CandidateSwitchConflictException staleSource(String runId) {
        return new CandidateSwitchConflictException(
                STALE_SOURCE,
                "原任务已不在当前消息路径，请刷新会话后重试: runId=" + runId);
    }
}
