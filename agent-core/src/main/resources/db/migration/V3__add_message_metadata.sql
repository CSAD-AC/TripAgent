ALTER TABLE `message`
    ADD COLUMN `metadata` TEXT DEFAULT NULL COMMENT '结构化元数据(JSON: tool_calls/tool_response 等)' AFTER `content`;
