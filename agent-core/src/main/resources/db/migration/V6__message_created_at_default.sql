-- P0 修复: message.created_at 恒 NULL。
-- 主修复在 PersistentChatMemory.toEntities() 显式 setCreatedAt(自定义 @Insert 不触发
-- MyMetaObjectHandler 自动填充);此处加 DEFAULT 兜底,防止未来新增写入路径再漏。
-- 注: DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) 需 MySQL 5.6.5+,本项目目标版本满足。
ALTER TABLE `message`
    MODIFY COLUMN `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间';
