-- 消息表新增计费用途的输入/输出 token 拆分列(token_count 机制)
-- token_count 已有(total_tokens), 这里补 prompt/completion 供按输入/输出计费
ALTER TABLE `message`
    ADD COLUMN `prompt_tokens` INT DEFAULT NULL COMMENT '计费: 输入 token 数(usage.prompt_tokens)' AFTER `token_count`,
    ADD COLUMN `completion_tokens` INT DEFAULT NULL COMMENT '计费: 输出 token 数(usage.completion_tokens)' AFTER `prompt_tokens`;