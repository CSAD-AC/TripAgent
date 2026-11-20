CREATE TABLE `user_memory` (
    `id`                    VARCHAR(36)  NOT NULL COMMENT '记忆 UUID',
    `user_id`               VARCHAR(64)  NOT NULL COMMENT '所属用户 ID(认证后)',
    `memory_type`           VARCHAR(24)  NOT NULL COMMENT 'preference / default / profile',
    `memory_key`            VARCHAR(96)  NOT NULL COMMENT '归一化记忆键',
    `memory_value`          VARCHAR(255) NOT NULL COMMENT '归一化记忆值',
    `source_conversation_id` VARCHAR(36) NOT NULL COMMENT '首次/最近写入会话',
    `confidence`            DECIMAL(4,3) NOT NULL DEFAULT 0.800 COMMENT '抽取置信度',
    `status`                VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active / deleted',
    `created_at`            DATETIME(3)  NOT NULL COMMENT '创建时间',
    `updated_at`            DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_memory_active_key` (`user_id`, `memory_key`, `status`),
    KEY `idx_user_memory_user_status` (`user_id`, `status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户长期记忆表';
