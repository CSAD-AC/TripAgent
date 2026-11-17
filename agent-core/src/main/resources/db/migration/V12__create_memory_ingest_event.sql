CREATE TABLE `memory_ingest_event` (
    `id`              VARCHAR(36)  NOT NULL COMMENT '事件 UUID',
    `tenant_id`       VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `user_id`         VARCHAR(64)  NOT NULL COMMENT '所属用户 ID',
    `conversation_id` VARCHAR(36)  NULL COMMENT '来源会话 ID',
    `event_type`      VARCHAR(24)  NOT NULL COMMENT 'message/tool_result/correction/delete',
    `payload_json`    TEXT         NOT NULL COMMENT '序列化输入(最大 8KB)',
    `event_status`    VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/processing/done/failed/skipped',
    `attempt_count`   INT          NOT NULL DEFAULT 0 COMMENT '处理次数',
    `last_error`      VARCHAR(512) NULL COMMENT '最近错误',
    `created_at`      DATETIME(3)  NOT NULL COMMENT '创建时间',
    `processed_at`    DATETIME(3)  NULL COMMENT '处理完成时间',
    PRIMARY KEY (`id`),
    KEY `idx_memory_ingest_status` (`event_status`, `created_at`),
    KEY `idx_memory_ingest_user` (`tenant_id`, `user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='长期记忆异步抽取事件表';
