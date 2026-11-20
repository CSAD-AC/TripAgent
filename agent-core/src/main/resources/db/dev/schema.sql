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

DROP TABLE IF EXISTS memory_revision;
DROP TABLE IF EXISTS memory_ingest_event;
DROP TABLE IF EXISTS memory_item;
DROP TABLE IF EXISTS user_memory;

CREATE TABLE IF NOT EXISTS memory_block (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    block_type VARCHAR(16) NOT NULL,
    content CLOB NOT NULL,
    char_count INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    generated_edited_at TIMESTAMP(3),
    last_change_source VARCHAR(24) NOT NULL DEFAULT 'INIT',
    last_changed_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uk_memory_block UNIQUE (user_id, block_type)
);

CREATE TABLE IF NOT EXISTS memory_user_prompt (
    user_id VARCHAR(64) PRIMARY KEY,
    content CLOB NOT NULL,
    char_count INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL
);

CREATE TABLE IF NOT EXISTS memory_raw_event (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64),
    message_id BIGINT,
    event_key VARCHAR(64) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    candidate_json CLOB,
    source VARCHAR(16) NOT NULL,
    event_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    result_status VARCHAR(24),
    result_json CLOB,
    error_message VARCHAR(500),
    attempt_count INT NOT NULL DEFAULT 0,
    processed_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uk_raw_event_key UNIQUE (user_id, event_key)
);

CREATE INDEX IF NOT EXISTS idx_raw_event_pending ON memory_raw_event(event_status, created_at);
CREATE INDEX IF NOT EXISTS idx_raw_event_user_status ON memory_raw_event(user_id, event_status, created_at);

CREATE TABLE IF NOT EXISTS memory_block_revision (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    block_type VARCHAR(16) NOT NULL,
    old_content CLOB,
    new_content CLOB NOT NULL,
    change_source VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_block_revision ON memory_block_revision(user_id, block_type, created_at);

CREATE TABLE IF NOT EXISTS memory_consolidation_watermark (
    user_id VARCHAR(64) PRIMARY KEY,
    last_message_id BIGINT,
    last_run_at TIMESTAMP(3)
);
