-- 本脚本在修复 RESUMABLE Binding 查询后由 DBA 手工执行，不随应用启动自动运行。
-- 每个 Relay 会话只保留最近更新且具有 runtime_session_id 的一条记录，其余记录保留审计事实并取消恢复资格。
START TRANSACTION;

WITH ranked_resumable AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY tenant_id, user_id, chat_session_id, provider
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS binding_rank
    FROM fin_ex_runtime_binding_t
    WHERE provider = 'relay'
      AND status = 'RESUMABLE'
      AND runtime_session_id IS NOT NULL
),
bindings_to_cancel AS (
    SELECT id
    FROM ranked_resumable
    WHERE binding_rank > 1

    UNION

    SELECT id
    FROM fin_ex_runtime_binding_t
    WHERE provider = 'relay'
      AND status = 'RESUMABLE'
      AND runtime_session_id IS NULL
)
UPDATE fin_ex_runtime_binding_t
SET status = 'CANCELLED',
    updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT id FROM bindings_to_cancel)
  AND provider = 'relay'
  AND status = 'RESUMABLE';

COMMIT;
