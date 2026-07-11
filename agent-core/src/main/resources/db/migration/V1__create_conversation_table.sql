CREATE TABLE `conversation` (
    `id`            VARCHAR(36)  NOT NULL COMMENT 'UUID 主键',
    `user_id`       VARCHAR(64)  NOT NULL DEFAULT 'anonymous' COMMENT '用户标识(暂无认证,默认 anonymous)',
    `title`         VARCHAR(255)          DEFAULT NULL COMMENT '对话标题(首条消息自动摘要)',
    `mode`          VARCHAR(16)  NOT NULL DEFAULT 'react' COMMENT '对话模式: react / graph',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT '状态: active / archived / deleted',
    `message_count` INT          NOT NULL DEFAULT 0 COMMENT '消息总数(冗余,加速列表展示)',
    `model`         VARCHAR(64)           DEFAULT NULL COMMENT '使用的模型名',
    `first_message` VARCHAR(500)          DEFAULT NULL COMMENT '首条消息摘要(列表预览)',
    `created_at`    DATETIME(3)  NOT NULL COMMENT '创建时间',
    `updated_at`    DATETIME(3)  NOT NULL COMMENT '最后活动时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_updated_at` (`updated_at` DESC),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='对话表';
