CREATE TABLE `user_account` (
    `id`                  VARCHAR(36)  NOT NULL COMMENT '用户 UUID',
    `username`            VARCHAR(64)  NOT NULL COMMENT '归一化登录名(小写)',
    `password_hash`       VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    `display_name`        VARCHAR(64)  NOT NULL COMMENT '显示名称',
    `status`              VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active / locked / disabled',
    `failed_login_count`  INT          NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    `locked_until`        DATETIME(3)           DEFAULT NULL COMMENT '临时锁定截止时间',
    `last_login_at`       DATETIME(3)           DEFAULT NULL COMMENT '最近成功登录时间',
    `created_at`          DATETIME(3)  NOT NULL COMMENT '创建时间',
    `updated_at`          DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_account_username` (`username`),
    KEY `idx_user_account_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户账号表';
