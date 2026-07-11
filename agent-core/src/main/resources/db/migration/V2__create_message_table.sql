CREATE TABLE `message` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `conversation_id` VARCHAR(36)  NOT NULL COMMENT '所属对话 ID(逻辑关联,无级联)',
    `role`            VARCHAR(16)  NOT NULL COMMENT '角色: user / assistant / system / tool',
    `content`         TEXT         NOT NULL COMMENT '消息文本内容',
    `model`           VARCHAR(64)           DEFAULT NULL COMMENT '生成此消息的模型名',
    `token_count`     INT                   DEFAULT NULL COMMENT 'Token 消耗数',
    `trace_id`        VARCHAR(8)            DEFAULT NULL COMMENT '链路追踪 ID',
    `sequence_num`    INT          NOT NULL DEFAULT 0 COMMENT '会话内序号(保证顺序)',
    `created_at`      DATETIME(3)  NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_conv_seq` (`conversation_id`, `sequence_num`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='消息表';
