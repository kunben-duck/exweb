/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.document;

/**
 * 文档库资产状态。
 *
 * <p>当前版本由统一后端接收文件并写入对象存储，上传完成即可进入 AVAILABLE。
 * 后续接入异步解析、病毒扫描或检索索引时，可以在不改变聊天附件协议的情况下扩展 PROCESSING、
 * INDEXING 等状态。</p>
 */
public enum DocumentStatus {
    /** 文档已上传并可被聊天引用。 */
    AVAILABLE,
    /** 文档正在处理，暂时不能作为聊天附件使用。 */
    PROCESSING,
    /** 文档处理失败，不能作为聊天附件使用。 */
    FAILED,
    /** 文档已被用户或系统删除，不再出现在文档库中。 */
    DELETED
}
