CREATE TABLE `memory_item` (
    `id`                    VARCHAR(36)   NOT NULL COMMENT '记忆项 UUID',
    `tenant_id`             VARCHAR(64)   NOT NULL DEFAULT 'default' COMMENT '租户 ID',
    `user_id`               VARCHAR(64)   NOT NULL COMMENT '所属用户 ID(认证后)',
    `project_id`            VARCHAR(64)   NULL COMMENT '项目/工作空间 scope',
    `memory_type`           VARCHAR(24)   NOT NULL COMMENT 'profile/preference/constraint/veto/episodic',
    `memory_key`            VARCHAR(96)   NOT NULL COMMENT '归一化记忆键',
    `memory_value`          VARCHAR(255)  NOT NULL COMMENT '归一化记忆值',
    `content`               VARCHAR(512)  NOT NULL COMMENT '展示文本',
    `attributes_json`       TEXT          NULL COMMENT '结构化扩展属性',
    `source_type`           VARCHAR(24)   NOT NULL COMMENT 'user_message/assistant_message/tool_result/import/system',
    `source_conversation_id` VARCHAR(36)  NULL COMMENT '来源会话 ID',
    `source_message_id`     VARCHAR(36)   NULL COMMENT '来源消息 ID',
    `confidence`            DECIMAL(4,3)  NOT NULL DEFAULT 0.800 COMMENT '置信度',
    `importance`            DECIMAL(4,3)  NOT NULL DEFAULT 0.500 COMMENT '重要度',
    `memory_status`         VARCHAR(16)   NOT NULL DEFAULT 'active' COMMENT 'active/deleted/superseded/rejected',
    `superseded_by`         VARCHAR(36)   NULL COMMENT '替代当前记忆项的 ID',
    `expires_at`            DATETIME(3)   NULL COMMENT '过期时间',
    `embedding_status`      VARCHAR(16)   NOT NULL DEFAULT 'not_required' COMMENT 'not_required/pending/indexed/failed/deleted',
    `embedding_model`       VARCHAR(64)   NULL COMMENT 'Embedding 模型',
    `embedding_version`     VARCHAR(64)   NULL COMMENT 'Embedding 版本',
    `extractor_version`     VARCHAR(64)   NOT NULL DEFAULT 'rule-v1' COMMENT '抽取器版本',
    `created_at`            DATETIME(3)   NOT NULL COMMENT '创建时间',
    `updated_at`            DATETIME(3)   NOT NULL COMMENT '更新时间',
    `deleted_at`            DATETIME(3)   NULL COMMENT '删除时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_memory_item_scope_key` (
        `tenant_id`, `user_id`, `project_id`, `memory_type`, `memory_key`, `memory_status`
    ),
    KEY `idx_memory_item_user_status` (`tenant_id`, `user_id`, `memory_status`, `memory_type`, `updated_at`),
    KEY `idx_memory_item_expiry` (`tenant_id`, `user_id`, `memory_status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='通用用户长期记忆表';
