-- conversation 表新增历史摘要列(滑动窗口压缩后存根, 加载时前缀注入)
-- 默认 NULL 表示未压缩; 由 HistoryCompactor 在消息超窗口后异步写入
ALTER TABLE `conversation`
    ADD COLUMN `summary` TEXT DEFAULT NULL COMMENT '对话历史 LLM 摘要(滑动窗口压缩存根)' AFTER `first_message`;