package uno.zhuchen.agent.tools.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import uno.zhuchen.agent.tools.cloud.config.SearchConfig;
import uno.zhuchen.agent.tools.cloud.model.ChannelStatus;
import uno.zhuchen.agent.tools.cloud.model.SearchOutcome;
import uno.zhuchen.agent.tools.cloud.model.SearchResult;
import uno.zhuchen.agent.tools.rag.service.RrfFuser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 云端搜索服务 - 多通道并行编排 + RRF 融合 + 单路故障降级
 *
 * <p>默认策略 {@code provider=parallel}: 百度 Qianfan 与 DeepSeek Search 同时发起,
 * 按<b>名次</b>用 RRF 融合(不按分数, 两路分数量纲不可比), 跨通道去重后截断。
 *
 * <p><b>故障隔离</b>: 每一路在独立线程执行并自行捕获异常, 超时由 Future 守卫。
 * 任一路故障/超时/零结果/未配置, 只损失该路贡献, 整体搜索继续返回其余通道的结果;
 * 全部通道都不可用时才返回空列表(由调用方降级为"搜索暂不可用"提示)。
 * 每一路的状态(含未配置与故障原因)都通过 {@link SearchOutcome#channels()} 透出, 不静默。
 *
 * <p>为何不是单通道: 实测两路站点重合度仅 0.03, 且各有硬伤 - 百度在中文长尾攻略被
 * SEO 软文农场占满(某 query Top12 里 10 条同一篇), DeepSeek 在大陆票务/价格类大量
 * 返回港台繁体页与外汇报价(且 localization / lang:zh 均无法纠正)。任何单通道都会在
 * 某一类旅游核心场景上整体失效。
 *
 * <p>legacy: {@code provider} 仍支持 {@code baidu} / {@code deepseek} / {@code tavily} /
 * {@code baidu-tavily-fallback}, 便于排查与灰度。
 */
@Service
public class CloudSearchService {

    private static final Logger log = LoggerFactory.getLogger(CloudSearchService.class);

    /** provider 缺省值 */
    private static final String DEFAULT_PROVIDER = "parallel";

    /** 通道名常量 */
    private static final String CH_BAIDU = "baidu";
    private static final String CH_DEEPSEEK = "deepseek";
    private static final String CH_TAVILY = "tavily";

    /** 并行通道集合与固定上报顺序 */
    private static final List<String> PARALLEL_CHANNELS = List.of(CH_BAIDU, CH_DEEPSEEK);

    /**
     * 线程数上限
     *
     * <p>线程池是所有并发搜索请求共享的, 不是"每通道一个线程"。单次搜索占用
     * 与通道数相同数量的线程(默认 2), 因此上限按并发搜索数估算: MCP 侧并发工具调用
     * 通常不超过 8(agent-core 的 MAX_CONCURRENT_TOOLS=4), 32 足够。
     *
     * <p>配合 SynchronousQueue + CallerRunsPolicy: 队列不排队(避免排队时间被算进超时预算),
     * 池满时任务降级到调用线程执行, 不会阻塞也不会丢任务。
     */
    private static final int MAX_POOL_THREADS = 32;

    /** 空闲线程存活时间(秒) */
    private static final long POOL_KEEP_ALIVE_SECONDS = 60L;

    /** 未配置时的通道超时兜底(秒) */
    private static final int DEFAULT_CHANNEL_TIMEOUT_SECONDS = 30;

    /** 未配置时的 HTTP 超时兜底(秒) */
    private static final int DEFAULT_HTTP_TIMEOUT_SECONDS = 12;

    private final SearchConfig config;
    private final ExecutorService executor;

    /** 通道客户端; Key 未配置时为 null, 表示该通道不参与 */
    private final SearchClient baiduClient;
    private final SearchClient deepseekClient;
    private final SearchClient tavilyClient;

    public CloudSearchService(SearchConfig config, ObjectMapper objectMapper) {
        this(config, createClients(config, objectMapper));
    }

    /**
     * 测试入口: 注入通道客户端, 不创建真实 WebClient.
     */
    CloudSearchService(SearchConfig config, SearchClient baiduClient,
                       SearchClient deepseekClient, SearchClient tavilyClient) {
        this(config, new Clients(baiduClient, deepseekClient, tavilyClient,
                resolveHttpTimeout(config)));
    }

    private CloudSearchService(SearchConfig config, Clients clients) {
        this.config = config;
        this.baiduClient = clients.baidu();
        this.deepseekClient = clients.deepseek();
        this.tavilyClient = clients.tavily();
        this.executor = newThreadPool();

        log.info("云端搜索初始化: provider={}, baidu={}, deepseek={}, tavily={}, http超时={}s, 通道守卫={}s",
                config.getProvider(), enabled(baiduClient), enabled(deepseekClient),
                enabled(tavilyClient), clients.httpTimeoutSeconds(), resolveChannelTimeout());
    }

    private static Clients createClients(SearchConfig config, ObjectMapper objectMapper) {
        WebClient webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();

        int httpTimeout = resolveHttpTimeout(config);
        SearchClient baidu = hasText(config.getBaiduApiKey())
                ? new BaiduSearchClient(webClient, objectMapper, config.getBaiduApiKey(), httpTimeout)
                : null;
        SearchClient deepseek = hasText(config.getDeepseekApiKey())
                ? new DeepSeekSearchClient(webClient, objectMapper, config.getDeepseekApiKey(),
                        config.getDeepseekBaseUrl(), httpTimeout)
                : null;
        SearchClient tavily = hasText(config.getTavilyApiKey())
                ? new TavilySearchClient(webClient, objectMapper, config.getTavilyApiKey(), httpTimeout)
                : null;
        return new Clients(baidu, deepseek, tavily, httpTimeout);
    }

    private static ExecutorService newThreadPool() {
        return new ThreadPoolExecutor(0, MAX_POOL_THREADS, POOL_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS, new SynchronousQueue<>(),
                new NamedDaemonThreadFactory("cloud-search-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 关闭线程池, 避免容器停机时遗留非守护线程。
     */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 执行云端搜索(只要结果, 不关心通道状态)。失败时返回空列表, 绝不抛异常。
     *
     * @param query 搜索词
     * @param limit 返回结果上限
     * @return 融合后的结果, 可能为空
     */
    public List<SearchResult> search(String query, int limit) {
        return searchDetailed(query, limit).results();
    }

    /**
     * 执行云端搜索并返回逐通道状态, 供调用方展示降级情况。
     *
     * @param query 搜索词
     * @param limit 返回结果上限
     * @return 结果 + 通道状态; 绝不抛异常
     */
    public SearchOutcome searchDetailed(String query, int limit) {
        if (query == null || query.isBlank()) {
            return SearchOutcome.empty();
        }
        int n = Math.max(limit, 1);
        String provider = provider();

        try {
            SearchOutcome outcome = switch (provider) {
                case CH_BAIDU -> runSequential(query, n, CH_BAIDU);
                case CH_DEEPSEEK -> runSequential(query, n, CH_DEEPSEEK);
                case CH_TAVILY -> runSequential(query, n, CH_TAVILY);
                case "baidu-tavily-fallback" -> runFallback(query, n);
                case "parallel" -> runParallel(query, n);
                default -> {
                    log.warn("未知的搜索 provider: {}, 回退到 parallel", provider);
                    yield runParallel(query, n);
                }
            };
            logOutcome(query, provider, outcome);
            return outcome;
        } catch (Exception e) {
            // 兜底: 编排层任何意外都不应让搜索链路挂掉
            log.error("搜索编排异常: query={}, provider={}", query, provider, e);
            return SearchOutcome.empty();
        }
    }

    // ==================== 编排 ====================

    /**
     * 单通道执行(用于 baidu / deepseek / tavily 显式指定)。
     */
    private SearchOutcome runSequential(String query, int limit, String channel) {
        ChannelAttempt attempt = runChannel(channel, query, limit);
        return combine(List.of(attempt), limit);
    }

    /**
     * 百度 -> 失败则 Tavily 的顺序降级(legacy 策略)。
     */
    private SearchOutcome runFallback(String query, int limit) {
        ChannelAttempt baidu = runChannel(CH_BAIDU, query, limit);
        if (baidu.contributed()) {
            return combine(List.of(baidu), limit);
        }
        log.info("百度通道未产出结果, 降级到 Tavily: query={}", query);
        ChannelAttempt tavily = runChannel(CH_TAVILY, query, limit);
        return combine(List.of(baidu, tavily), limit);
    }

    /**
     * 双通道并行执行 + 融合
     *
     * <p>要点:
     * <ul>
     *   <li>未配置 key 的通道也写入状态("not configured"), 否则默认配置下会静默退化成单路</li>
     *   <li>所有通道共用一个 deadline, 而不是每个 future 各给一份守卫时长, 避免总耗时叠加</li>
     *   <li>被中断时立刻取消剩余任务并返回已收集的部分结果, 不继续空转</li>
     * </ul>
     */
    private SearchOutcome runParallel(String query, int limit) {
        Map<String, ChannelAttempt> byName = new LinkedHashMap<>();
        List<String> scheduled = new ArrayList<>(PARALLEL_CHANNELS.size());
        List<Future<ChannelAttempt>> futures = new ArrayList<>(PARALLEL_CHANNELS.size());
        List<Long> submittedAt = new ArrayList<>(PARALLEL_CHANNELS.size());

        for (String name : PARALLEL_CHANNELS) {
            if (clientOf(name) == null) {
                byName.put(name, new ChannelAttempt(name, List.of(), 0L, "not configured"));
                log.warn("通道 {} 未配置 api key, 本次跳过(已计入通道状态)", name);
                continue;
            }
            Callable<ChannelAttempt> task = () -> runChannel(name, query, limit);
            try {
                futures.add(executor.submit(task));
                scheduled.add(name);
                submittedAt.add(System.nanoTime());
            } catch (RejectedExecutionException e) {
                byName.put(name, new ChannelAttempt(name, List.of(), 0L, "rejected: pool saturated"));
                log.warn("通道 {} 任务被线程池拒绝: {}", name, e.getMessage());
            }
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(resolveChannelTimeout());
        for (int i = 0; i < futures.size(); i++) {
            String name = scheduled.get(i);
            long submitNanos = submittedAt.get(i);
            long remaining = deadline - System.nanoTime();
            try {
                if (remaining <= 0L) {
                    throw new TimeoutException();
                }
                byName.put(name, futures.get(i).get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
                byName.put(name, new ChannelAttempt(name, List.of(), elapsedMs(submitNanos), "timeout"));
                log.warn("通道 {} 超过通道守卫 {}s, 已弃用该路", name, resolveChannelTimeout());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                byName.put(name, new ChannelAttempt(name, List.of(), elapsedMs(submitNanos), brief(cause)));
                log.warn("通道 {} 执行失败, 已弃用该路: {}", name, brief(cause));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                byName.put(name, new ChannelAttempt(name, List.of(), elapsedMs(submitNanos), "interrupted"));
                // 取消剩余任务并把它们标成中断, 避免线程继续被占用、也避免后续 get 反复抛中断
                for (int j = i; j < futures.size(); j++) {
                    futures.get(j).cancel(true);
                    byName.putIfAbsent(scheduled.get(j),
                            new ChannelAttempt(scheduled.get(j), List.of(), elapsedMs(submittedAt.get(j)), "interrupted"));
                }
                log.warn("等待通道 {} 时被中断, 已取消剩余通道任务", name);
                break;
            }
        }

        List<ChannelAttempt> attempts = new ArrayList<>(byName.size());
        for (String name : PARALLEL_CHANNELS) {
            ChannelAttempt attempt = byName.get(name);
            if (attempt != null) {
                attempts.add(attempt);
            }
        }
        if (attempts.stream().noneMatch(ChannelAttempt::contributed)) {
            ChannelAttempt fallback = runTavilyFallback(query, limit, deadline);
            if (fallback != null) {
                attempts.add(fallback);
            }
        }
        return combine(attempts, limit);
    }

    /**
     * 双主通道均未产出时, 在同一个总截止时间内尝试 Tavily.
     */
    private ChannelAttempt runTavilyFallback(String query, int limit, long deadline) {
        if (tavilyClient == null) {
            return null;
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            return new ChannelAttempt(CH_TAVILY, List.of(), 0L,
                    "fallback skipped: deadline exhausted");
        }
        long submittedAt = System.nanoTime();
        Future<ChannelAttempt> future;
        try {
            future = executor.submit(() -> runChannel(CH_TAVILY, query, limit));
        } catch (RejectedExecutionException e) {
            return new ChannelAttempt(CH_TAVILY, List.of(), 0L,
                    "rejected: pool saturated");
        }
        try {
            log.info("双主通道无产出, 使用剩余 {}ms 尝试 Tavily 兜底: query={}",
                    TimeUnit.NANOSECONDS.toMillis(remaining), query);
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new ChannelAttempt(CH_TAVILY, List.of(), elapsedMs(submittedAt),
                    "fallback timeout");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new ChannelAttempt(CH_TAVILY, List.of(), elapsedMs(submittedAt), brief(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new ChannelAttempt(CH_TAVILY, List.of(), elapsedMs(submittedAt), "interrupted");
        }
    }

    /**
     * 在独立线程中执行单通道, 通道自身异常在此消化为 ChannelAttempt。
     */
    private ChannelAttempt runChannel(String name, String query, int limit) {
        SearchClient client = clientOf(name);
        if (client == null) {
            return new ChannelAttempt(name, List.of(), 0L, "not configured");
        }
        long started = System.nanoTime();
        try {
            return new ChannelAttempt(name, client.search(query, limit), elapsedMs(started), null);
        } catch (SearchChannelException e) {
            return new ChannelAttempt(name, List.of(), elapsedMs(started), brief(e));
        } catch (Exception e) {
            return new ChannelAttempt(name, List.of(), elapsedMs(started), brief(e));
        }
    }

    // ==================== 融合 ====================

    /**
     * 合并多通道结果(纯函数, 便于单测)
     *
     * <ul>
     *   <li>0 路有产出 -> 空结果, 全部状态保留(供上层判断是否彻底失败)</li>
     *   <li>1 路有产出 -> 该路结果直接截断(客户端已按同一 limit 去过重, 无需再跑一遍)</li>
     *   <li>2 路以上 -> RRF 按名次融合 + 跨通道去重</li>
     * </ul>
     *
     * @param attempts 各通道执行结果, 顺序即上报顺序
     * @param limit    返回条数上限
     * @return 融合结果 + 状态
     */
    static SearchOutcome combine(List<ChannelAttempt> attempts, int limit) {
        List<ChannelStatus> statuses = attempts.stream()
                .map(a -> new ChannelStatus(a.name(), a.hits().size(), a.latencyMs(), a.error()))
                .collect(Collectors.toList());

        List<ChannelAttempt> contributing = attempts.stream()
                .filter(ChannelAttempt::contributed)
                .collect(Collectors.toList());

        if (contributing.isEmpty()) {
            return new SearchOutcome(List.of(), statuses);
        }
        if (contributing.size() == 1) {
            List<SearchResult> hits = contributing.get(0).hits();
            int end = Math.min(limit, hits.size());
            return new SearchOutcome(hits.subList(0, end), statuses);
        }
        return new SearchOutcome(fuse(contributing, limit), statuses);
    }

    /**
     * RRF 融合 + 跨通道去重
     *
     * <p>用 {@link RrfFuser} 而不是加权分数: 百度 authority 是 0.5~1.0 且 rerank 恒为 1.0,
     * DeepSeek authority 是 0~4、rerank 有梯度, 两路分数完全不可比, 按名次融合才是无偏的。
     *
     * <p>跨通道去重按两个信号: 归一化标题相同, 或正文判定为同一篇(包含度)。两路对同一篇
     * 文章的摘录长度差可达 5 倍, 对称的 Jaccard 阈值会漏判, 详见 {@link SearchText#sameArticle}。
     *
     * @param contributing 有产出的通道
     * @param limit        返回条数上限
     * @return 融合后的结果
     */
    private static List<SearchResult> fuse(List<ChannelAttempt> contributing, int limit) {
        Map<String, SearchResult> byUrl = new LinkedHashMap<>();
        List<List<String>> rankedLists = new ArrayList<>(contributing.size());
        for (ChannelAttempt attempt : contributing) {
            List<String> ids = new ArrayList<>(attempt.hits().size());
            for (SearchResult r : attempt.hits()) {
                SearchResult existing = byUrl.get(r.url());
                // 同 URL 在两路都出现: 名次两边都计入(RRF 语义), 展示字段择优合并
                byUrl.put(r.url(), existing == null ? r : SearchText.merge(existing, r));
                ids.add(r.url());
            }
            rankedLists.add(ids);
        }

        List<Map.Entry<String, Double>> fused = RrfFuser.fuse(rankedLists);
        List<SearchResult> out = new ArrayList<>(Math.min(limit, fused.size()));
        Set<String> keptTitles = new HashSet<>();
        List<String> keptBodies = new ArrayList<>();
        for (Map.Entry<String, Double> entry : fused) {
            if (out.size() >= limit) {
                break;
            }
            SearchResult r = byUrl.get(entry.getKey());
            if (r == null) {
                continue;
            }
            String normTitle = SearchText.normalizeTitle(r.title());
            if (!normTitle.isEmpty() && !keptTitles.add(normTitle)) {
                continue;
            }
            String body = r.snippet() == null ? "" : r.snippet();
            boolean duplicated = false;
            for (String kept : keptBodies) {
                if (SearchText.sameArticle(kept, body)) {
                    duplicated = true;
                    break;
                }
            }
            if (duplicated) {
                continue;
            }
            keptBodies.add(body);
            out.add(r);
        }
        return out;
    }

    // ==================== 内部结构 ====================

    /**
     * 单通道执行记录(内部用, 含结果本体)。
     *
     * @param name      通道名
     * @param hits      结果
     * @param latencyMs 耗时(从提交任务起算)
     * @param error     错误, null 表示成功
     */
    record ChannelAttempt(String name, List<SearchResult> hits, long latencyMs, String error) {

        /**
         * @return 既没出错又至少 1 条
         */
        boolean contributed() {
            return error == null && hits != null && !hits.isEmpty();
        }
    }

    private record Clients(SearchClient baidu, SearchClient deepseek, SearchClient tavily,
                           int httpTimeoutSeconds) {
    }

    /**
     * 具名守护线程工厂, 便于线程栈排查。
     */
    private static final class NamedDaemonThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger();

        NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    // ==================== 小工具 ====================

    private SearchClient clientOf(String name) {
        return switch (name) {
            case CH_BAIDU -> baiduClient;
            case CH_DEEPSEEK -> deepseekClient;
            case CH_TAVILY -> tavilyClient;
            default -> null;
        };
    }

    private String provider() {
        String p = config.getProvider();
        return p == null || p.isBlank() ? DEFAULT_PROVIDER : p.trim().toLowerCase(Locale.ROOT);
    }

    private int resolveChannelTimeout() {
        int s = config.getChannelTimeoutSeconds();
        return s > 0 ? s : DEFAULT_CHANNEL_TIMEOUT_SECONDS;
    }

    private static int resolveHttpTimeout(SearchConfig config) {
        int s = config.getHttpTimeoutSeconds();
        return s > 0 ? s : DEFAULT_HTTP_TIMEOUT_SECONDS;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String enabled(SearchClient client) {
        return client == null ? "off" : "on";
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String brief(Throwable e) {
        String msg = e.getMessage();
        String text = msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    /**
     * 输出一行可读的通道状态, 用于观察降级是否发生。
     */
    private static void logOutcome(String query, String provider, SearchOutcome outcome) {
        String status = outcome.channels().stream()
                .map(c -> c.name() + "=" + c.hits() + "/" + c.latencyMs() + "ms"
                        + (c.ok() ? "" : "(" + c.error() + ")"))
                .collect(Collectors.joining(", "));
        if (outcome.results().isEmpty()) {
            log.warn("搜索无结果: provider={}, 通道[{}], 失败[{}]", provider, status, outcome.failures());
        } else {
            log.info("搜索完成: provider={}, 结果={} 条, 通道[{}], 贡献={}, 降级={}",
                    provider, outcome.results().size(), status,
                    outcome.contributedChannels(), outcome.degraded());
        }
    }
}
