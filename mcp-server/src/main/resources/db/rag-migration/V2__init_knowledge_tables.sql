-- RAG 知识库初始化: 知识文档表 + 知识分块表
-- 数据主权: MySQL 为源(source of truth), Milvus 仅为可重建的向量索引

-- 知识文档表 (一次入库的单位: 手工上传 / 自沉淀卡片 / 规划报告)
CREATE TABLE IF NOT EXISTS knowledge_doc (
    id          VARCHAR(32)  NOT NULL COMMENT '文档主键 (ASSIGN_UUID)',
    title       VARCHAR(255) NOT NULL COMMENT '文档标题',
    source_type VARCHAR(32)  NOT NULL COMMENT '来源类型: manual / self_precip / graph_report / url / seed',
    source_ref  VARCHAR(512) NULL     COMMENT '溯源引用: traceId / conversationId / URL',
    status      VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT '索引状态: pending / processing / indexed / failed',
    chunk_count INT          NOT NULL DEFAULT 0 COMMENT '分块数量',
    error_msg   VARCHAR(1024) NULL    COMMENT '入库失败原因 (status=failed 时)',
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_doc_status (status),
    KEY idx_doc_source_type (source_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'RAG 知识文档表';

-- 知识分块表 (检索的最小单位, 与 Milvus collection 主键一致)
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id               VARCHAR(32) NOT NULL COMMENT '分块主键 (与 Milvus 主键一致)',
    doc_id           VARCHAR(32) NOT NULL COMMENT '所属文档 ID',
    seq              INT          NOT NULL COMMENT '文档内分块序号',
    content          TEXT         NOT NULL COMMENT '分块原文',
    char_count       INT          NOT NULL DEFAULT 0 COMMENT '字符数',
    destination      VARCHAR(64)  NULL     COMMENT '目的地 (metadata, 过滤检索用)',
    theme            VARCHAR(64)  NULL     COMMENT '主题 (metadata, 过滤检索用)',
    embedding_status VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT '向量化状态: pending / indexed / failed',
    created_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chunk_doc_seq (doc_id, seq),
    KEY idx_chunk_doc (doc_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'RAG 知识分块表';

-- 全文索引 (混合检索的关键词路, ngram 支持中文)
CREATE FULLTEXT INDEX ft_chunk_content ON knowledge_chunk (content) WITH PARSER ngram;
