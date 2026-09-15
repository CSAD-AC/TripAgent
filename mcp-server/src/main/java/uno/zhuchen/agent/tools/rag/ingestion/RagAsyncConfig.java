package uno.zhuchen.agent.tools.rag.ingestion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * RAG 知识入库异步执行器配置.
 *
 * <p>为保证入库顺序与避免并发写冲突, ingestion 使用单线程执行器
 * ({@code ragIngestionExecutor}), 长文档解析/分块/向量化在后台线程完成,
 * 上传接口立即返回 doc_id 供前端轮询状态(对应 {@code RAG_PLAN.md} 3.3).
 */
@Configuration
@EnableAsync
public class RagAsyncConfig {

    /**
     * 知识入库单线程执行器.
     *
     * @return 名为 ragIngestionExecutor 的任务执行器
     */
    @Bean(name = "ragIngestionExecutor")
    public ThreadPoolTaskExecutor ragIngestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("rag-ingest-");
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }
}