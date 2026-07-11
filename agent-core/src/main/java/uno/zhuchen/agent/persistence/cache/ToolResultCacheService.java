package uno.zhuchen.agent.persistence.cache;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 工具调用结果缓存。
 *
 * 防护层（从外到内）:
 *   1. BloomFilter —— 拦截肯定不存在的 key（穿透防护）
 *   2. Redis GET   —— 快速命中直接返回
 *   3. SET NX 锁   —— 热点 key 只允许一个请求重建缓存（击穿防护）
 *   4. TTL 随机化  —— 基准值 + 20% 随机偏移，避免雪崩
 *
 * 可观测性:
 *   - 命中/未命中计数（按工具维度）
 *   - 操作延迟分布（P50/P95/P99）
 *   - 缓存值大小分布
 */
@Slf4j
@Service
public class ToolResultCacheService {

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    private static final String TOOL_CACHE_PREFIX = "tool:";

    // 各工具 TTL（基准秒数，实际叠加 0~20% 随机抖动）
    private static final Map<String, Long> TOOL_TTL_SECONDS = Map.ofEntries(
        Map.entry("amapWeather",       1800L),   // 30min
        Map.entry("amapPoiSearch",     3600L),   // 60min
        Map.entry("amapPoiAround",     3600L),
        Map.entry("amapDrivingRoute",  7200L),   // 120min
        Map.entry("amapWalkingRoute",  7200L),
        Map.entry("amapBicyclingRoute",7200L),
        Map.entry("amapTransitRoute",  7200L),
        Map.entry("amapGeocode",       86400L),  // 24h
        Map.entry("amapReverseGeocode",86400L),
        Map.entry("stationQuery",      86400L),  // 车站信息几乎不变
        Map.entry("trainRouteQuery",   7200L),
        Map.entry("ticketQuery",       120L),    // 余票实时变化,仅 2min
        Map.entry("webSearch",         600L),    // 10min
        Map.entry("pageFetch",         600L),
        Map.entry("weather",           1800L)
    );

    private static final long DEFAULT_TTL = 600L; // 10min

    // ============ 缓存穿透防护：本地布隆过滤器 ============
    // 预计 10 万条缓存 key，1% 误判率 -> 约 120KB 内存
    private final BloomFilter<String> bloomFilter = BloomFilter.create(
            Funnels.stringFunnel(StandardCharsets.UTF_8),
            100_000, 0.01);

    // ============ 可观测性：Micrometer 指标 ============

    private final Counter cacheHitTotal;
    private final Counter cacheMissTotal;
    private final Counter cacheLockContention;
    private final Timer cacheGetLatency;
    private final Timer cachePutLatency;
    private final DistributionSummary cacheValueSize;

    public ToolResultCacheService(StringRedisTemplate redisTemplate,
                                   MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;

        this.cacheHitTotal = Counter.builder("cache.hit.total")
                .description("Total cache hits")
                .tag("layer", "tool").register(meterRegistry);
        this.cacheMissTotal = Counter.builder("cache.miss.total")
                .description("Total cache misses")
                .tag("layer", "tool").register(meterRegistry);
        this.cacheLockContention = Counter.builder("cache.lock.contention")
                .description("Cache lock contention count")
                .register(meterRegistry);
        this.cacheGetLatency = Timer.builder("cache.operation.latency")
                .description("Cache operation latency")
                .tag("operation", "get")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.cachePutLatency = Timer.builder("cache.operation.latency")
                .description("Cache operation latency")
                .tag("operation", "put")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.cacheValueSize = DistributionSummary.builder("cache.value.size")
                .description("Cached value size in bytes")
                .baseUnit("bytes")
                .register(meterRegistry);
    }

    /**
     * 尝试从缓存获取工具结果。
     */
    public Optional<String> get(String toolName, String argsJson) {
        String key = buildKey(toolName, argsJson);

        // 1. BloomFilter 预检：key 肯定不存在则直接返回
        if (!bloomFilter.mightContain(key)) {
            cacheMissTotal.increment();
            meterRegistry.counter("cache.miss.by_tool", "tool", toolName).increment();
            return Optional.empty();
        }

        // 2. 查 Redis（计时）
        Optional<String> result = cacheGetLatency.record(() -> {
            String cached = redisTemplate.opsForValue().get(key);
            return Optional.ofNullable(cached);
        });

        if (result.isPresent()) {
            cacheHitTotal.increment();
            meterRegistry.counter("cache.hit.by_tool", "tool", toolName).increment();
        } else {
            cacheMissTotal.increment();
            meterRegistry.counter("cache.miss.by_tool", "tool", toolName).increment();
        }

        return result;
    }

    /**
     * 写入工具结果缓存。
     * TTL 自动叠加随机偏移防止缓存雪崩。
     */
    public void put(String toolName, String argsJson, String result) {
        String key = buildKey(toolName, argsJson);

        // 回填 BloomFilter
        bloomFilter.put(key);

        long baseTtl = TOOL_TTL_SECONDS.getOrDefault(toolName, DEFAULT_TTL);
        long jitter = ThreadLocalRandom.current().nextLong(0, (long) (baseTtl * 0.2) + 1);

        cachePutLatency.record(() ->
            redisTemplate.opsForValue().set(
                    key, result, Duration.ofSeconds(baseTtl + jitter)));

        cacheValueSize.record(result.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * 带分布式互斥锁的缓存读取（应对缓存击穿）。
     *
     * 只在 get() 未命中且确定需要查 MCP 时调用此方法。
     */
    public String getWithLock(String toolName, String argsJson,
                               Function<String, String> mcpCaller) {
        String key = buildKey(toolName, argsJson);
        String lockKey = "lock:" + key;
        String requestId = UUID.randomUUID().toString();

        // 尝试获取锁（5 秒自动过期防死锁）
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, requestId, Duration.ofSeconds(5));

        if (Boolean.TRUE.equals(locked)) {
            try {
                // 双重检查：锁等待期间可能已有其他线程写入了缓存
                String cached = redisTemplate.opsForValue().get(key);
                if (cached != null) return cached;

                // 查 MCP 并写缓存
                String result = mcpCaller.apply(argsJson);
                put(toolName, argsJson, result);
                return result;
            } finally {
                // Lua 脚本保证删除锁的原子性（只删自己的锁）
                String script =
                    "if redis.call('get', KEYS[1]) == ARGV[1] " +
                    "then return redis.call('del', KEYS[1]) " +
                    "else return 0 end";
                redisTemplate.execute(
                        RedisScript.of(script, Long.class),
                        List.of(lockKey), requestId);
            }
        }

        // 未获取到锁：短暂等待后重试读缓存
        cacheLockContention.increment();
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String retryResult = redisTemplate.opsForValue().get(key);
        if (retryResult != null) return retryResult;

        // 降级：直接调 MCP（不阻塞调用方）
        return mcpCaller.apply(argsJson);
    }

    /**
     * 构建缓存 key: "tool:{name}:{md5(argsJson)}"
     * 使用 MD5 对参数做 hash，hash 前用 TreeMap 规范化 JSON 键序。
     */
    private String buildKey(String toolName, String argsJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> sorted = mapper.readValue(argsJson, TreeMap.class);
            String canonical = mapper.writeValueAsString(sorted);
            String hash = DigestUtils.md5DigestAsHex(
                    canonical.getBytes(StandardCharsets.UTF_8));
            return TOOL_CACHE_PREFIX + toolName + ":" + hash;
        } catch (Exception e) {
            // 解析失败时回退到原始字符串 MD5
            String hash = DigestUtils.md5DigestAsHex(
                    argsJson.getBytes(StandardCharsets.UTF_8));
            return TOOL_CACHE_PREFIX + toolName + ":" + hash;
        }
    }
}
