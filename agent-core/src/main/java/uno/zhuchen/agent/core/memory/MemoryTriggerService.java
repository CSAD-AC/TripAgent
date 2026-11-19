package uno.zhuchen.agent.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
public class MemoryTriggerService {

    private static final Pattern STRONG_PREFERENCE = Pattern.compile(
            "(记住|请记得|我喜欢|我不喜欢|我偏好|我爱|我来自|我的家乡|我老家|我现在住在|"
                    + "我经常从|我一般|我通常|我平时|不吃|不能吃|忌|预算|几个人|人数)");
    private static final Pattern TRAVEL_CONTEXT = Pattern.compile(
            "(旅行|旅游|行程|出行|去哪|游玩|景点|酒店|机票|火车票|路线|预算|同行|亲子|美食|自然|文化|历史|购物|户外)");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(api[ _-]?key|password|passwd|身份证|银行卡|cvv|验证码|密钥)");

    private final MemoryIngestService ingestService;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final boolean hybridEnabled;
    private final int minChars;

    public MemoryTriggerService(MemoryIngestService ingestService,
                                ObjectMapper objectMapper,
                                @Value("${app.memory.ingest-trigger.enabled:true}") boolean enabled,
                                @Value("${app.memory.hybrid.enabled:false}") boolean hybridEnabled,
                                @Value("${app.memory.ingest-trigger.min-chars:6}") int minChars) {
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.hybridEnabled = hybridEnabled;
        this.minChars = Math.max(2, minChars);
    }

    /**
     * 混合记忆链路是否在跑。
     *
     * <p>混合链路开着时, 记忆应只由异步事件 worker 写入 memory_item;
     * 旧的规则链路({@code UserMemoryService.extractAndStore})必须让位,
     * 否则同一条用户消息会被抽两遍("不吃辣"就会被写成 PROFILE + PREFERENCE 两份)。
     */
    public boolean isHybridPipelineEnabled() {
        return hybridEnabled;
    }

    /**
     * 对话完成后触发。只处理当前用户消息，不扫描工具结果和助手输出。
     */
    public String onConversationFinished(String userId, String conversationId, String userMessage) {
        if (!enabled || userId == null || userId.isBlank()
                || userMessage == null || userMessage.isBlank()) {
            return null;
        }
        String message = userMessage.trim();
        if (message.length() < minChars || SENSITIVE.matcher(message).find()) {
            return null;
        }

        boolean strongSignal = STRONG_PREFERENCE.matcher(message).find();
        boolean travelContext = TRAVEL_CONTEXT.matcher(message).find();
        if (!strongSignal && !(travelContext && message.length() >= 20)) {
            return null;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("trigger", strongSignal ? "explicit_signal" : "travel_context");
        try {
            return ingestService.enqueue("default", userId, conversationId, "message",
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("[memory-trigger] 入队失败: userId={}, conversationId={}, error={}",
                    userId, conversationId, e.getMessage());
            return null;
        }
    }
}
