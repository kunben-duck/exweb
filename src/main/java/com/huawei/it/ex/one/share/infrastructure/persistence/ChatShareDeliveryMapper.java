package com.huawei.it.ex.one.share.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;

/**
 * fin_ex_chat_share_delivery_t 的 MyBatis Mapper。
 */
@Mapper
public interface ChatShareDeliveryMapper {
    /**
     * 插入分享发送记录。
     *
     * @param row 发送记录写入行，包含 provider、目标、链接、结果和错误信息。
     * @return 影响行数。
     */
    int insert(ChatShareDeliveryRow row);
}
