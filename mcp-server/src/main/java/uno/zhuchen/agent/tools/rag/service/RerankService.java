package uno.zhuchen.agent.tools.rag.service;

import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * DashScope Rerank 重排服务 — 对双路融合后的候选做相关性重排.
 *
 * <p>将融合后的候选列表按 query 与内容的语义相关性重排, 覆盖 RRF 的"仅名次"劣势.
 * 熔断/降级: Rerank 是增强环节并非必需, 超时或失败时返回 null, 调用方跳过重排
 * 改用 RRF 分数排序(degraded=rerank), 不阻断检索主链路.
 *
 * <p>使用注意: {@link RerankModel#call} 是同步阻塞调用, 通过独立线程池的
 * {@link Future#get(long, TimeUnit)} 做 2 秒超时控制, 超时即 cancel 降级.
 */
@Slf4j
@Service
public class RerankService {

    /** 重排超时时间(ms), 超过即判定降级 */
    private static final long TIMEOUT_MS = 2000;

    /** 重排线程池(单线程, 供 call 超时控制) */
    private final ExecutorService rerankExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rag-rerank");
        t.setDaemon(true);
        return t;
    });

    private final RerankModel rerankModel;

    /** 重排模型名(由 DashScope Rerank 自动配置注入) */
    private final String rerankModelName;

    /**
     * 构造注入重排模型与模型名.
     *
     * @param rerankModel     DashScope Rerank 模型(自动装配)
     * @param rerankModelName 重排模型名, 如 gte-rerank
     */
    public RerankService(RerankModel rerankModel,
                         @Value("${spring.ai.dashscope.rerank.options.model:}") String rerankModelName) {
        this.rerankModel = rerankModel;
        this.rerankModelName = rerankModelName;
    }

    /**
     * 对候选文档按 query 重排, 返回重排后(带重排分数)的前 topN 条.
     *
     * <p>候选为空或仅一条时无需调用模型, 直接短路返回(保留原分).
     * 超时/异常返回 null, 由调用方决定降级为 RRF 分数排序.
     *
     * @param query      检索问题
     * @param candidates 融合后的候选文档(RRF 已排序)
     * @param topN       返回条数
     * @return 重排后的文档列表(已设重排 score); 重排不可用时返回 null
     */
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        if (candidates.size() == 1) {
            // 单条候选无重排意义, 直接返回保留原始 score
            return candidates;
        }
        DashScopeRerankOptions options = DashScopeRerankOptions.builder()
                .topN(topN)
                .returnDocuments(true)
                .build();
        if (rerankModelName != null && !rerankModelName.isBlank()) {
            options.setModel(rerankModelName);
        }
        RerankRequest request = new RerankRequest(query, candidates, options);
        Future<RerankResponse> future = rerankExecutor.submit(() -> rerankModel.call(request));
        try {
            RerankResponse response = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            List<Document> reranked = response.getResults().stream()
                    .map(dws -> {
                        // 重排已按 query 相关性重排序, score 保留候选原分(仅顺序改变)
                        return dws.getOutput();
                    })
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            log.info("知识重排成功: query={}, candidates={}, topN={}", query, candidates.size(), reranked.size());
            return reranked;
        } catch (TimeoutException e) {
            log.warn("知识重排超时降级: query={}, timeoutMs={}", query, TIMEOUT_MS);
            return null;
        } catch (ExecutionException e) {
            log.warn("知识重排失败降级: query={}, error={}", query, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("知识重排线程中断降级: query={}", query);
            return null;
        } finally {
            // 超时/异常已消耗结果, 取消未完成的底层任务避免泄漏
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }
}