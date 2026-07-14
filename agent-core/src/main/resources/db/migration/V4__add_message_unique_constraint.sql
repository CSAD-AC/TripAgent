-- 1. 清理重复数据：相同 (conversation_id, sequence_num) 保留 id 最小的行
DELETE m1 FROM message m1
INNER JOIN message m2
    ON m1.conversation_id = m2.conversation_id
    AND m1.sequence_num = m2.sequence_num
    AND m1.id > m2.id;

-- 2. 追加唯一约束，防止后续重复插入
ALTER TABLE `message`
    ADD UNIQUE INDEX `uk_conv_seq` (`conversation_id`, `sequence_num`);
