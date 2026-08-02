ALTER TABLE fin_ex_chat_share_t
    ALTER COLUMN source_user_message_id DROP NOT NULL;

ALTER TABLE fin_ex_chat_share_t
    ALTER COLUMN source_assistant_message_id DROP NOT NULL;

COMMENT ON TABLE fin_ex_chat_share_t IS '聊天分享表，保存单轮问答或用户明确选择消息的固定展示快照。';
COMMENT ON COLUMN fin_ex_chat_share_t.source_user_message_id IS '首条来源 user 消息 ID；纯 assistant 多消息分享时为空。';
COMMENT ON COLUMN fin_ex_chat_share_t.source_assistant_message_id IS '首条来源 assistant 消息 ID；纯 user 多消息分享时为空。';
COMMENT ON COLUMN fin_ex_chat_share_t.source_run_id IS '单轮分享的来源 runId；多消息分享可能跨多个 run，因此为空。';
COMMENT ON COLUMN fin_ex_chat_share_t.title IS '分享标题；为空时由应用层根据首条非空来源消息生成。';
COMMENT ON COLUMN fin_ex_chat_share_t.scope IS '分享范围，SINGLE_TURN 或 SELECTED_MESSAGES。';
COMMENT ON COLUMN fin_ex_chat_share_t.snapshot_json IS '固定展示快照 JSON，保存单轮问答或明确选中的消息、visible=true 的 parts 和附件展示信息；不保存反馈、Cookie 或鉴权信息。';
COMMENT ON TABLE fin_ex_chat_share_delivery_t IS '聊天消息分享发送记录表，保存分享链接发送到 WeLink 等 provider 的请求摘要和发送结果。';
