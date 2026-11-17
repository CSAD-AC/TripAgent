CREATE TABLE `memory_revision` (
    `id`             VARCHAR(36)  NOT NULL COMMENT '修订 UUID',
    `memory_item_id` VARCHAR(36)  NOT NULL COMMENT '记忆项 ID',
    `tenant_id`      VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `user_id`        VARCHAR(64)  NOT NULL COMMENT '用户 ID',
    `operation`      VARCHAR(24)  NOT NULL COMMENT 'create/update/delete/supersede/restore',
    `previous_value` VARCHAR(512) NULL COMMENT '旧值',
    `new_value`      VARCHAR(512) NULL COMMENT '新值',
    `actor`          VARCHAR(64)  NOT NULL COMMENT '操作者',
    `reason`         VARCHAR(255) NULL COMMENT '原因',
    `created_at`     DATETIME(3)  NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_memory_revision_item` (`tenant_id`, `user_id`, `memory_item_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='长期记忆修订审计表';
