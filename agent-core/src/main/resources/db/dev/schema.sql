CREATE TABLE IF NOT EXISTS conversation (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL DEFAULT 'anonymous',
    title VARCHAR(255),
    mode VARCHAR(16) NOT NULL DEFAULT 'react',
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    message_count INT NOT NULL DEFAULT 0,
    model VARCHAR(128),
    first_message VARCHAR(500),
    summary CLOB,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_conversation_user ON conversation(user_id);
CREATE INDEX IF NOT EXISTS idx_conversation_updated ON conversation(updated_at);

CREATE TABLE IF NOT EXISTS message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content CLOB,
    metadata CLOB,
    model VARCHAR(128),
    token_count INT,
    prompt_tokens INT,
    completion_tokens INT,
    trace_id VARCHAR(64),
    sequence_num INT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uk_message_conversation_seq UNIQUE (conversation_id, sequence_num)
);

CREATE INDEX IF NOT EXISTS idx_message_conversation ON message(conversation_id, sequence_num);

CREATE TABLE IF NOT EXISTS user_memory (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    memory_type VARCHAR(24) NOT NULL,
    memory_key VARCHAR(96) NOT NULL,
    memory_value VARCHAR(255) NOT NULL,
    source_conversation_id VARCHAR(36) NOT NULL,
    confidence DECIMAL(4,3) NOT NULL DEFAULT 0.800,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uk_user_memory_active_key UNIQUE (user_id, memory_key, status)
);

CREATE INDEX IF NOT EXISTS idx_user_memory_user_status ON user_memory(user_id, status, updated_at);
