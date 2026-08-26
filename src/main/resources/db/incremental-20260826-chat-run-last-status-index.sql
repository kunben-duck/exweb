-- DBA需在事务外执行；并发创建索引，避免阻塞fin_ex_chat_run_t的正常DML。
CREATE INDEX CONCURRENTLY idx_fin_ex_chat_run_owner_session_created_id_status
    ON fin_ex_chat_run_t(tenant_id, user_id, session_id, created_at DESC, id DESC, status);
