package com.huawei.finance.front.one.application.command;

/**
 * 文档元数据更新命令。
 *
 * @param originalName 新展示文件名；为空时保留原名称。
 * @param metadataJson 新扩展元数据 JSON；为空时保留原值。
 */
public record DocumentUpdateCommand(
        String originalName,
        String metadataJson
) {}
