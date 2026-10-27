-- 仅用于已部署 VARCHAR 及 created_at/updated_at 的旧版观测表，由 DBA 在正确业务 schema 下手动执行。
-- 本脚本不随应用启动执行，不适用于已按新版定义建表的环境；重复执行会报错，不自动跳过或修复。
-- 执行前备份，并通过配置部署或重启关闭所有实例的观测，确认旧观测写入及相关事务已结束。
-- 类型变更可能重写表、处理索引并等待锁，须在维护窗口执行，提前核查磁盘空间和目标 openGauss 兼容性。
-- 使用同一数据库连接执行整个事务；任何语句失败后停止执行，并在该连接执行 ROLLBACK，不继续 COMMIT。
-- 提交成功后部署匹配新列名的 Mapper，再按需重新开启观测；不提供提交后的自动反向迁移或截断兜底。
START TRANSACTION;

ALTER TABLE fin_ex_turn_trace_t RENAME COLUMN created_at TO creation_date;
ALTER TABLE fin_ex_turn_trace_t RENAME COLUMN updated_at TO last_update_date;
ALTER TABLE fin_ex_agent_observation_t RENAME COLUMN created_at TO creation_date;
ALTER TABLE fin_ex_agent_observation_t RENAME COLUMN updated_at TO last_update_date;

ALTER TABLE fin_ex_turn_trace_t
    ALTER COLUMN trace_id TYPE NVARCHAR2(64),
    ALTER COLUMN tenant_id TYPE NVARCHAR2(64),
    ALTER COLUMN user_id TYPE NVARCHAR2(64),
    ALTER COLUMN session_id TYPE NVARCHAR2(64),
    ALTER COLUMN root_run_id TYPE NVARCHAR2(64),
    ALTER COLUMN root_user_message_id TYPE NVARCHAR2(64),
    ALTER COLUMN source_run_id TYPE NVARCHAR2(64),
    ALTER COLUMN source_trace_id TYPE NVARCHAR2(64),
    ALTER COLUMN current_run_id TYPE NVARCHAR2(64),
    ALTER COLUMN origin_type TYPE NVARCHAR2(32),
    ALTER COLUMN status TYPE NVARCHAR2(32),
    ALTER COLUMN error_code TYPE NVARCHAR2(128);

ALTER TABLE fin_ex_agent_observation_t
    ALTER COLUMN observation_id TYPE NVARCHAR2(64),
    ALTER COLUMN trace_id TYPE NVARCHAR2(64),
    ALTER COLUMN tenant_id TYPE NVARCHAR2(64),
    ALTER COLUMN user_id TYPE NVARCHAR2(64),
    ALTER COLUMN session_id TYPE NVARCHAR2(64),
    ALTER COLUMN run_id TYPE NVARCHAR2(64),
    ALTER COLUMN user_message_id TYPE NVARCHAR2(64),
    ALTER COLUMN parent_observation_id TYPE NVARCHAR2(64),
    ALTER COLUMN retry_of_observation_id TYPE NVARCHAR2(64),
    ALTER COLUMN agent_type TYPE NVARCHAR2(32),
    ALTER COLUMN agent_mode TYPE NVARCHAR2(32),
    ALTER COLUMN operation TYPE NVARCHAR2(32),
    ALTER COLUMN skill_id TYPE NVARCHAR2(128),
    ALTER COLUMN runtime_session_id TYPE NVARCHAR2(128),
    ALTER COLUMN status TYPE NVARCHAR2(32),
    ALTER COLUMN outcome TYPE NVARCHAR2(32),
    ALTER COLUMN reject_code TYPE NVARCHAR2(128),
    ALTER COLUMN reject_reason_code TYPE NVARCHAR2(128);

COMMENT ON COLUMN fin_ex_turn_trace_t.creation_date IS '快照首次创建UTC时间。';
COMMENT ON COLUMN fin_ex_turn_trace_t.last_update_date IS '快照最近业务采样UTC时间。';
COMMENT ON COLUMN fin_ex_agent_observation_t.creation_date IS '快照首次创建UTC时间。';
COMMENT ON COLUMN fin_ex_agent_observation_t.last_update_date IS '快照最近业务采样UTC时间。';

COMMIT;
