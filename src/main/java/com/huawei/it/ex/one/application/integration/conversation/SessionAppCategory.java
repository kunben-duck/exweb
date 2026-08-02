package com.huawei.it.ex.one.application.integration.conversation;

/**
 * 当前用户会话中的应用分类。
 *
 * @param appId 应用分类标识。
 * @param appName 最近一次保存的非空应用展示名称；不存在时为空。
 */
public record SessionAppCategory(
        String appId,
        String appName
) {}
