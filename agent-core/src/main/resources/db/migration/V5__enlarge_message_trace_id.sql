-- traceId 由 8 位扩至 16 位(2^64 空间,消除生日悖论碰撞),
-- 列宽必须同步放大,否则 MySQL 严格模式下超长插入报错
ALTER TABLE `message`
    MODIFY COLUMN `trace_id` VARCHAR(16) DEFAULT NULL COMMENT '链路追踪 ID(16 位 hex)';
