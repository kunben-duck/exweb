package com.huawei.it.ex.one.security.domain;

/**
 * 当前调用方身份信息。
 *
 * <p>当前阶段只承载用户身份，不表达权限范围。权限、角色、数据域等控制后续接入企业权限框架时，
 * 由专门的权限上下文或策略服务补充，避免把临时 scope 模型固化到领域层。</p>
 *
 * @param tenantId 当前租户标识，必须由应用身份上下文解析。
 * @param userId 当前用户原始标识，必须由应用身份上下文解析。
 * @param username 当前用户展示名或登录名。
 * @param userAccount 用户账号。
 * @param employeeNumber 用户工号；外网用户可为空。
 * @param userCN 用户中文姓名。
 * @param userType 用户类型。
 * @param uuid 企业用户 UUID。
 * @param employeeNameEng 用户英文工号/英文姓名扩展字段。
 * @param displayNameEn 英文展示名。
 * @param displayNameCn 中文展示名。
 * @param globalUserId 全局用户 ID；系统数据归属优先使用该字段。
 */
public record UserContext(
        String tenantId,
        String userId,
        String username,
        String userAccount,
        String employeeNumber,
        String userCN,
        String userType,
        String uuid,
        String employeeNameEng,
        String displayNameEn,
        String displayNameCn,
        Long globalUserId
) {
    public UserContext(String tenantId, String userId, String username) {
        this(tenantId, userId, username, userId, null, username, "UNKNOWN", userId,
                null, username, username, null);
    }

    public UserContext {
        userAccount = defaultText(userAccount, userId);
        userCN = defaultText(userCN, username);
        userType = defaultText(userType, "UNKNOWN");
        uuid = defaultText(uuid, userId);
        displayNameEn = defaultText(displayNameEn, username);
        displayNameCn = defaultText(displayNameCn, username);
    }

    /**
     * ChatService 内部数据隔离使用的用户标识。
     *
     * <p>企业框架接入后优先使用全局用户 ID；首版默认值未提供时回退现有 userId，
     * 避免改动数据库字段和本地开发配置。</p>
     */
    public String ownerUserId() {
        return globalUserId == null ? userId : String.valueOf(globalUserId);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
