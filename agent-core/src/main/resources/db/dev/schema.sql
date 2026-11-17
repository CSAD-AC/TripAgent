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

CREATE TABLE IF NOT EXISTS memory_item (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    user_id VARCHAR(64) NOT NULL,
    project_id VARCHAR(64),
    memory_type VARCHAR(24) NOT NULL,
    memory_key VARCHAR(96) NOT NULL,
    memory_value VARCHAR(255) NOT NULL,
    content VARCHAR(512) NOT NULL,
    attributes_json TEXT,
    source_type VARCHAR(24) NOT NULL,
    source_conversation_id VARCHAR(36),
    source_message_id VARCHAR(36),
    confidence DECIMAL(4,3) NOT NULL DEFAULT 0.800,
    importance DECIMAL(4,3) NOT NULL DEFAULT 0.500,
    memory_status VARCHAR(16) NOT NULL DEFAULT 'active',
    superseded_by VARCHAR(36),
    expires_at TIMESTAMP(3),
    embedding_status VARCHAR(16) NOT NULL DEFAULT 'not_required',
    embedding_model VARCHAR(64),
    embedding_version VARCHAR(64),
    extractor_version VARCHAR(64) NOT NULL DEFAULT 'rule-v1',
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    deleted_at TIMESTAMP(3),
    CONSTRAINT uk_memory_item_scope_key UNIQUE (tenant_id, user_id, project_id, memory_type, memory_key, memory_status)
);

CREATE INDEX IF NOT EXISTS idx_memory_item_user_status ON memory_item(tenant_id, user_id, memory_status, memory_type, updated_at);
CREATE INDEX IF NOT EXISTS idx_memory_item_expiry ON memory_item(tenant_id, user_id, memory_status, expires_at);

CREATE TABLE IF NOT EXISTS memory_ingest_event (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(36),
    event_type VARCHAR(24) NOT NULL,
    payload_json TEXT NOT NULL,
    event_status VARCHAR(16) NOT NULL DEFAULT 'pending',
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(512),
    created_at TIMESTAMP(3) NOT NULL,
    processed_at TIMESTAMP(3)
);

CREATE INDEX IF NOT EXISTS idx_memory_ingest_status ON memory_ingest_event(event_status, created_at);
CREATE INDEX IF NOT EXISTS idx_memory_ingest_user ON memory_ingest_event(tenant_id, user_id, created_at);

CREATE TABLE IF NOT EXISTS memory_revision (
    id VARCHAR(36) PRIMARY KEY,
    memory_item_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    user_id VARCHAR(64) NOT NULL,
    operation VARCHAR(24) NOT NULL,
    previous_value VARCHAR(512),
    new_value VARCHAR(512),
    actor VARCHAR(64) NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMP(3) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_memory_revision_item ON memory_revision(tenant_id, user_id, memory_item_id, created_at);
