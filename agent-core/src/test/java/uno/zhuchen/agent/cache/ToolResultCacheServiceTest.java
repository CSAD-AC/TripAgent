package uno.zhuchen.agent.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uno.zhuchen.agent.persistence.cache.ToolResultCacheService;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolResultCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private MeterRegistry meterRegistry;
    private ToolResultCacheService cacheService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cacheService = new ToolResultCacheService(redisTemplate, meterRegistry);
    }

    @Test
    void get_shouldReturnEmptyForNewKey() {
        // given - BloomFilter doesn't contain key yet
        String argsJson = "{\"city\":\"北京\"}";

        // when
        Optional<String> result = cacheService.get("amapWeather", argsJson);

        // then
        assertFalse(result.isPresent());
    }

    @Test
    void get_shouldReturnValueAfterPut() {
        // given
        String argsJson = "{\"city\":\"北京\"}";
        cacheService.put("amapWeather", argsJson, "晴 25°C");
        when(valueOps.get(anyString())).thenReturn("晴 25°C");

        // when
        Optional<String> result = cacheService.get("amapWeather", argsJson);

        // then
        assertTrue(result.isPresent());
        assertEquals("晴 25°C", result.get());
    }

    @Test
    void put_shouldSetCacheWithTtl() {
        // given
        String argsJson = "{\"city\":\"上海\"}";

        // when
        cacheService.put("amapWeather", argsJson, "多云 20°C");

        // then - TTL should be 1800 + random(0~360)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(anyString(), anyString(), ttlCaptor.capture());
        Duration ttl = ttlCaptor.getValue();
        assertTrue(ttl.getSeconds() >= 1800 && ttl.getSeconds() <= 2160,
                "TTL should be base(1800) + jitter(0-360), but got " + ttl.getSeconds());
    }

    @Test
    void getWithLock_shouldObtainLockAndCallMcp() {
        // given
        String argsJson = "{\"city\":\"广州\"}";
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        // when
        String result = cacheService.getWithLock("amapWeather", argsJson,
                args -> "广州 30°C");

        // then
        assertEquals("广州 30°C", result);

        // verify lock was released via Lua script
        verify(redisTemplate).execute(any(RedisScript.class), any(List.class), anyString());
    }

    @Test
    void getWithLock_whenLockContention_shouldWaitAndRetry() {
        // given
        String argsJson = "{\"city\":\"深圳\"}";
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(valueOps.get(anyString())).thenReturn("深圳 28°C");

        // when
        String result = cacheService.getWithLock("amapWeather", argsJson,
                args -> "深圳 28°C");

        // then - got cached value from retry, not from MCP
        assertEquals("深圳 28°C", result);
    }

    @Test
    void getWithLock_whenLockContentionAndCacheMiss_shouldFallbackToMcp() {
        // given
        String argsJson = "{\"city\":\"珠海\"}";
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(valueOps.get(anyString())).thenReturn(null);

        // when
        String result = cacheService.getWithLock("amapWeather", argsJson,
                args -> "珠海 26°C (fallback)");

        // then - fell back to direct MCP call
        assertEquals("珠海 26°C (fallback)", result);
    }

    @Test
    void put_shouldRecordMetrics() {
        // given
        cacheService.put("amapWeather", "{\"city\":\"测试\"}", "晴");

        // then - metrics should be recorded
        assertNotNull(meterRegistry.find("cache.hit.total").counter());
        assertNotNull(meterRegistry.find("cache.operation.latency").timer());
        assertNotNull(meterRegistry.find("cache.value.size").summary());
    }
}
