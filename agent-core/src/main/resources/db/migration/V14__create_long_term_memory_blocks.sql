DROP TABLE IF EXISTS memory_revision;
DROP TABLE IF EXISTS memory_ingest_event;
DROP TABLE IF EXISTS memory_item;
DROP TABLE IF EXISTS user_memory;

CREATE TABLE memory_block (
    id                  VARCHAR(64)  NOT NULL PRIMARY KEY,
    user_id             VARCHAR(64)  NOT NULL,
    block_type          VARCHAR(16)  NOT NULL,
    content             TEXT         NOT NULL,
    char_count          INT          NOT NULL DEFAULT 0,
    version             INT          NOT NULL DEFAULT 0,
    generated_edited_at DATETIME     NULL,
    last_change_source  VARCHAR(24)  NOT NULL DEFAULT 'INIT',
    last_changed_at     DATETIME     NULL,
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    UNIQUE KEY uk_memory_block (user_id, block_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memory_user_prompt (
    user_id     VARCHAR(64) NOT NULL PRIMARY KEY,
    content     TEXT        NOT NULL,
    char_count  INT         NOT NULL DEFAULT 0,
    version     INT         NOT NULL DEFAULT 0,
    updated_at  DATETIME    NOT NULL,
    created_at  DATETIME    NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memory_raw_event (
    id              VARCHAR(64)   NOT NULL PRIMARY KEY,
    user_id         VARCHAR(64)   NOT NULL,
    conversation_id VARCHAR(64)   NULL,
    message_id      BIGINT        NULL,
    event_key       VARCHAR(64)   NOT NULL,
    content         VARCHAR(2000) NOT NULL,
    source          VARCHAR(16)   NOT NULL,
    event_status    VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempt_count   INT           NOT NULL DEFAULT 0,
    processed_at    DATETIME      NULL,
    created_at      DATETIME      NOT NULL,
    UNIQUE KEY uk_raw_event_key (user_id, event_key),
    KEY idx_raw_event_pending (event_status, created_at),
    KEY idx_raw_event_user_status (user_id, event_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memory_block_revision (
    id            VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id       VARCHAR(64) NOT NULL,
    block_type    VARCHAR(16) NOT NULL,
    old_content   TEXT        NULL,
    new_content   TEXT        NOT NULL,
    change_source VARCHAR(16) NOT NULL,
    created_at    DATETIME    NOT NULL,
    KEY idx_block_revision (user_id, block_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memory_consolidation_watermark (
    user_id         VARCHAR(64) NOT NULL PRIMARY KEY,
    last_message_id BIGINT      NULL,
    last_run_at     DATETIME    NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
