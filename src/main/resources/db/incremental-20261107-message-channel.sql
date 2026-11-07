-- DBA manual migration only; not executed on application startup.
-- Run in the correct business schema before deploying the new message Mapper.
-- Back up first and verify the target openGauss version in a maintenance window.
-- ADD COLUMN still requires a table lock, even without a default or backfill.
-- NOWAIT fails instead of waiting for busy readers/writers; do not retry in a loop.
-- Set a suitable session statement timeout before execution according to DBA policy.
-- On any error, issue ROLLBACK and stop; do not continue in a failed transaction.
-- One-time migration: an existing channel column is an error, not silently skipped.
BEGIN;
LOCK TABLE fin_ex_chat_message_t IN ACCESS EXCLUSIVE MODE NOWAIT;
ALTER TABLE fin_ex_chat_message_t ADD COLUMN channel NVARCHAR2(64);
COMMENT ON COLUMN fin_ex_chat_message_t.channel IS '首次创建消息的请求端侧来源；旧消息未知时为空，复用消息不覆盖，分支快照保留来源。';
COMMIT;
