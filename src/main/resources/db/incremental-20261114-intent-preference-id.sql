-- DBA手动在正确业务schema中执行，不随应用启动运行。
-- 仅用于尚无preference_intent_id列的已部署偏好表；先执行本脚本，再部署新版Mapper。
-- 不回填旧数据，不新增默认值或索引。请在目标环境验证DDL锁等待，并选择维护窗口执行。
-- 切换期间暂停偏好写入，完成所有写入实例升级后恢复，避免旧Mapper更新名称而保留新版本写入的旧ID。
BEGIN;

ALTER TABLE fin_ex_intent_preference_correction_t
    ADD COLUMN preference_intent_id NVARCHAR2(128);

COMMENT ON COLUMN fin_ex_intent_preference_correction_t.preference_intent_id IS '用户选择的偏好意图ID；来自已有选择结果，缺失或无效时为空，不作为可信路由身份。';

COMMIT;
