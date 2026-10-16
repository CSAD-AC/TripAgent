package uno.zhuchen.agent.tools.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeChunkMapper;
import uno.zhuchen.agent.tools.rag.model.KnowledgeChunk;
import uno.zhuchen.agent.tools.rag.model.RetrievalResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 混合检索服务单元测试.
 *
 * <p>用 Mockito 隔离 Milvus/MySQL/Rerank 三个外部依赖, 验证核心编排逻辑:
 * ① 正常路径双路召回+RRF+重排, degraded=none;
 * ② L1 缓存命中时直接返回, 不再触发召回(外部零调用);
 * ③ 重排失败降级为 RRF 序(degraded=rerank), 主链路不中断;
 * ④ 目的地精确过滤为空时 filter_relaxed 放宽全库重召回一次;
 * ⑤ 两路皆空且无目的地时返回空结果 + hint.
 */
class HybridRetrievalServiceTest {

    private VectorStore vectorStore;
    private KnowledgeChunkMapper chunkMapper;
    private RerankService rerankService;
    private HybridRetrievalService service;

    @BeforeEach
    void setUp() {
        vectorStore = Mockito.mock(VectorStore.class);
        chunkMapper = Mockito.mock(KnowledgeChunkMapper.class);
        rerankService = Mockito.mock(RerankService.class);
        service = new HybridRetrievalService(vectorStore, chunkMapper, rerankService, new ObjectMapper());
        // 默认: 关键词路返回空(仅向量路参与)
        when(chunkMapper.selectList(any())).thenReturn(List.of());
    }

    /** 构造带 metadata 的向量命中 Document */
    private Document doc(String id, String title, String content) {
        Map<String, Object> meta = Map.of(
                "docId", id,
                "docTitle", title,
                "destination", "北京",
                "theme", "景点",
                "source", "seed");
        return new Document(id, content, meta);
    }

    /** 正常路径: 向量路命中 + 重排成功, degraded=none, results 有值 */
    @Test
    void retrieve_normalPathReturnsHits() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("v1", "故宫", "故宫游览路线")));
        when(rerankService.rerank(any(), anyList(), anyInt()))
                .thenReturn(List.of(doc("v1", "故宫", "故宫游览路线")));

        RetrievalResponse resp = service.retrieve("故宫怎么玩", "北京", null);

        assertEquals("none", resp.getDegraded());
        assertEquals(1, resp.getResults().size());
        assertEquals("故宫游览路线", resp.getResults().get(0).getContent());
        assertNull(resp.getHint(), "正常路径无 hint");
        assertNotNull(resp.getTookMs());
    }

    /** L1 缓存命中: 同 query 第二次调用直接返回缓存, 不再触发向量/关键词召回 */
    @Test
    void retrieve_l1CacheHitSkipsRecall() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("v1", "故宫", "故宫游览路线")));
        when(rerankService.rerank(any(), anyList(), anyInt()))
                .thenReturn(List.of(doc("v1", "故宫", "故宫游览路线")));

        RetrievalResponse first = service.retrieve("故宫怎么玩", "北京", null);
        RetrievalResponse second = service.retrieve("故宫怎么玩", "北京", null);

        assertEquals(first.getResults().size(), second.getResults().size());
        // 二次调用命中缓存, 召回入口只被首次调用触发
        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
    }

    /** 重排失败降级: rerank 返回 null 时回退到 RRF 序, degraded 含 rerank, 主链路不中断 */
    @Test
    void retrieve_rerankNullFallsBackToRrf() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("v1", "故宫", "故宫游览路线")));
        when(rerankService.rerank(any(), anyList(), anyInt())).thenReturn(null);

        RetrievalResponse resp = service.retrieve("故宫怎么玩", "北京", null);

        assertTrue(resp.getDegraded().contains("rerank"), "应标记 rerank 降级");
        assertEquals(1, resp.getResults().size(), "降级后仍返回 RRF 序候选");
    }

    /** filter_relaxed: 目的地精确过滤为空时, 放宽为全库重召回一次并标记 */
    @Test
    void retrieve_filterRelaxedExpandsToFullLibrary() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(rerankService.rerank(any(), anyList(), anyInt())).thenReturn(List.of());

        RetrievalResponse resp = service.retrieve("凤凰古城怎么玩", "凤凰", null);

        assertTrue(resp.getDegraded().contains("filter_relaxed"), "应标记 filter_relaxed");
        assertTrue(resp.getResults().isEmpty(), "两路皆空则结果为空");
        assertNotNull(resp.getHint(), "空结果时给出转 webSearch 的 hint");
        // 首次(带目的地) + 放宽(全库) 共触发两次向量召回
        verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
    }

    /** 空候选: 无目的地且两路皆空时, 返回空结果 + hint, degraded 为 none(非服务故障) */
    @Test
    void retrieve_emptyAllRoadsReturnsEmpty() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(rerankService.rerank(any(), anyList(), anyInt())).thenReturn(List.of());

        RetrievalResponse resp = service.retrieve("不存在的内容", null, null);

        assertEquals("none", resp.getDegraded());
        assertTrue(resp.getResults().isEmpty());
        assertNotNull(resp.getHint());
    }

    /**
     * 构造生产口径的向量命中 Document: {@code id} 是 chunk 主键(= Milvus 主键),
     * metadata 里的 {@code docId} 是**父文档 id**。
     *
     * <p>与 {@link #doc} 不同, 这里刻意让两者不相等 —— 这正是修复前的 bug 现场,
     * 用于锁死"融合 key 必须取 chunk 主键"这一不变量。
     */
    private Document chunkDoc(String chunkId, String parentDocId, String title, String content) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("docId", parentDocId);
        meta.put("docTitle", title);
        meta.put("destination", "北京");
        meta.put("theme", "景点");
        meta.put("source", "seed");
        return new Document(chunkId, content, meta);
    }

    /** 构造关键词路命中分块 */
    private KnowledgeChunk chunk(String chunkId, String parentDocId, String content) {
        KnowledgeChunk c = new KnowledgeChunk();
        c.setId(chunkId);
        c.setDocId(parentDocId);
        c.setContent(content);
        c.setDestination("北京");
        c.setTheme("景点");
        return c;
    }

    /**
     * 回归: RRF 两路必须用同一个 id 空间(chunk 主键), 跨路增强才会生效。
     *
     * <p>修复前向量路取 metadata 的父文档 id、关键词路取 chunk 主键, 同一分块在两路
     * key 不同 → 融合里永远不叠加; 本用例断言同时命中两路的分块融合分严格高于
     * 只命中一路的分块(即跨路增强真实发生)。
     */
    @Test
    void retrieve_rrfFusesCrossPathOnChunkId() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(
                        chunkDoc("c1", "docA", "故宫", "故宫游览路线"),
                        chunkDoc("c2", "docA", "故宫", "故宫门票信息")));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("c1", "docA", "故宫游览路线"),
                chunk("c3", "docB", "景山公园视角")));
        // 重排不可用 → 直接返回 null, 由服务回退到 RRF 序, 便于断言融合分顺序
        when(rerankService.rerank(any(), anyList(), anyInt())).thenReturn(null);

        RetrievalResponse resp = service.retrieve("故宫怎么玩", "北京", null);

        assertEquals(3, resp.getResults().size(), "三条不同 chunk 都应作为候选保留");
        assertEquals("故宫游览路线", resp.getResults().get(0).getContent(),
                "同时命中双路的 c1 应排在只命中单路的 c2/c3 之前(跨路增强生效)");
    }

    /**
     * 回归: 同一父文档的多个 chunk 不得互相覆盖。
     *
     * <p>修复前用父文档 id 建索引, docA 的 c1 会被 c2 覆盖, 向量路候选坍缩成一条;
     * 本用例断言两个 chunk 都在最终结果中。
     */
    @Test
    void retrieve_sameParentMultipleChunksNotCollapsed() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(
                        chunkDoc("c1", "docA", "故宫", "故宫游览路线"),
                        chunkDoc("c2", "docA", "故宫", "故宫门票信息")));
        when(rerankService.rerank(any(), anyList(), anyInt()))
                .thenReturn(List.of(
                        chunkDoc("c1", "docA", "故宫", "故宫游览路线"),
                        chunkDoc("c2", "docA", "故宫", "故宫门票信息")));

        RetrievalResponse resp = service.retrieve("故宫怎么玩", "北京", null);

        List<String> contents = resp.getResults().stream().map(h -> h.getContent()).toList();
        assertEquals(2, contents.size(), "同一父文档的两个 chunk 都应保留, 不应坍缩为一条");
        assertTrue(contents.contains("故宫游览路线"), "c1 不应被 c2 覆盖");
        assertTrue(contents.contains("故宫门票信息"), "c2 应存在");
    }

    /** 命中条目的 docId 仍是父文档 id(展示口径不变), 融合 key 与展示字段职责分离 */
    @Test
    void retrieve_hitStillExposesParentDocId() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunkDoc("c1", "docA", "故宫", "故宫游览路线")));
        when(rerankService.rerank(any(), anyList(), anyInt()))
                .thenReturn(List.of(chunkDoc("c1", "docA", "故宫", "故宫游览路线")));

        RetrievalResponse resp = service.retrieve("故宫怎么玩", "北京", null);

        assertEquals("docA", resp.getResults().get(0).getDocId(),
                "命中条目对外仍展示父文档 id, 不受融合 key 变更影响");
    }
}