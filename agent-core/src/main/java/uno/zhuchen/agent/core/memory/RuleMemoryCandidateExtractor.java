package uno.zhuchen.agent.core.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class RuleMemoryCandidateExtractor implements MemoryCandidateExtractor {

    private static final BigDecimal DEFAULT_CONFIDENCE = new BigDecimal("0.800");
    private static final BigDecimal DEFAULT_IMPORTANCE = new BigDecimal("0.600");

    private final MemoryExtractor extractor;
    private final ObjectMapper objectMapper;

    public RuleMemoryCandidateExtractor(MemoryExtractor extractor, ObjectMapper objectMapper) {
        this.extractor = extractor;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<MemoryExtractionResult> extract(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return List.of();
        }
        String message = payloadJson;
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            if (node.hasNonNull("message")) {
                message = node.get("message").asText();
            }
        } catch (Exception e) {
            // 事件 payload 允许是纯文本，解析失败时按原始文本处理。
        }
        if (looksLikeCredential(message)) {
            return List.of();
        }

        List<MemoryExtractionResult> results = new ArrayList<>();
        for (MemoryCandidate candidate : extractor.extract(message)) {
            MemoryItemType type = switch (candidate.type()) {
                case "profile" -> MemoryItemType.PROFILE;
                case "default" -> MemoryItemType.PREFERENCE;
                default -> MemoryItemType.PREFERENCE;
            };
            String value = candidate.value() == null ? "" : candidate.value().trim();
            if (value.isEmpty()) {
                continue;
            }
            results.add(new MemoryExtractionResult(
                    type,
                    candidate.key(),
                    value,
                    candidate.key() + "=" + value,
                    DEFAULT_CONFIDENCE,
                    DEFAULT_IMPORTANCE,
                    null,
                    null));
        }
        return results;
    }

    private static boolean looksLikeCredential(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("api key") || lower.contains("password") || lower.contains("passwd")
                || lower.contains("身份证") || lower.contains("银行卡") || lower.contains("cvv");
    }
}
