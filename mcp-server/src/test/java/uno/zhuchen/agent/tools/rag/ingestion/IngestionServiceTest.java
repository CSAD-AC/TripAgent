package uno.zhuchen.agent.tools.rag.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.vectorstore.VectorStore;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeChunkMapper;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeDocMapper;
import uno.zhuchen.agent.tools.rag.model.KnowledgeDoc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IngestionService 去重与入库单元测试.
 *
 * <p>用 Mockito 隔离 MySQL/Milvus/异步 worker, 验证:
 * ① 同 sourceRef+sourceType 已存在时去重直接返回既有 docId, 不重复入库;
 * ② 不存在时新增 doc 并调度 worker.
 *
 * <p>注: MyBatis-Plus BaseMapper 的 insert 存在单参/集合两个重载,
 * 这里用 {@link #insert()} 强类型匹配器(predicate, 泛型锁定 KnowledgeDoc)
 * 只匹配单参 insert(T), 规避多载下 any() 无法判定方法引用的问题.
 */
class IngestionServiceTest {

    private KnowledgeDocMapper docMapper;
    private KnowledgeChunkMapper chunkMapper;
    private IngestionWorker worker;
    private IngestionService service;

    @BeforeEach
    void setUp() {
        docMapper = Mockito.mock(KnowledgeDocMapper.class);
        chunkMapper = Mockito.mock(KnowledgeChunkMapper.class);
        worker = Mockito.mock(IngestionWorker.class);
        service = new IngestionService(
                Mockito.mock(VectorStore.class),
                docMapper, chunkMapper, worker,
                500, 60, 50);
    }

    /** 单参 insert 匹配器: 泛型锁定为 KnowledgeDoc, 只匹配 insert(T) 重载 */
    private static KnowledgeDoc insert() {
        return argThat(x -> true);
    }

    /** 同 sourceRef + sourceType 已入库: 去重返回既有 id, 不 insert、不调度 worker */
    @Test
    void submit_existingRefDedupes() {
        String existingId = "existing-doc-001";
        when(docMapper.selectCount(any())).thenReturn(1L);
        KnowledgeDoc old = new KnowledgeDoc();
        old.setId(existingId);
        old.setSourceType("self_precip");
        old.setSourceRef("msg-1");
        when(docMapper.selectOne(any())).thenReturn(old);

        String result = service.submit("标题", "内容", "self_precip", "msg-1", null, null);

        assertEquals(existingId, result, "去重后应返回既有 docId");
        verify(docMapper, never()).insert(insert());
        verify(worker, never()).processDocument(
                any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    /** 未存在: 新增 doc 并调度 worker 异步入库 */
    @Test
    void submit_newRefInsertsAndSchedules() {
        when(docMapper.selectCount(any())).thenReturn(0L);
        // insert 回填 id(MyBatis-Plus ASSIGN_UUID 于 insert 时赋值)
        when(docMapper.insert(insert())).thenAnswer(inv -> {
            KnowledgeDoc doc = inv.getArgument(0);
            doc.setId("generated-id");
            return 1;
        });

        String result = service.submit("标题", "内容", "self_precip", "msg-2", "杭州", "美食");

        assertEquals("generated-id", result);
        verify(docMapper).insert(insert());
        verify(worker).processDocument(
                any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
    }
}