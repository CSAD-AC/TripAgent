package uno.zhuchen.agent.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import uno.zhuchen.agent.common.Result;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息点赞与知识自沉淀 REST — 用户点赞一条优质 assistant 回答, 触发其沉淀进知识库.
 *
 * <p>闭环: 前端点赞某条消息 → 本接口将消息内容(sourceRef=messageId, sourceType=self_precip)
 * 提交 mcp-server 入库(异步分块/向量化); 相同 messageId 再次点赞由 mcp-server 去重跳过.
 * 沉淀后知识库可通过 knowledgeSearch 检索到该优质回答(对应 {@code RAG_PLAN.md} 7D).
 */
@Slf4j
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final WebClient mcpClient;

    /**
     * 构造注入 mcp-server 地址并建立沉淀推送 WebClient.
     *
     * @param mcpServerUrl mcp-server 地址, 默认 http://localhost:8081
     */
    public MessageController(@Value("${mcp.server-url:http://localhost:8081}") String mcpServerUrl) {
        this.mcpClient = WebClient.builder().baseUrl(mcpServerUrl).build();
    }

    /**
     * 点赞消息并沉淀进知识库.
     *
     * @param messageId 消息主键(作为知识 sourceRef 溯源)
     * @param body      {conversationId, content, destination?}
     *                  其中 content 为被点赞的 assistant 回答全文.
     * @return {docId, status} 或错误
     */
    @PostMapping("/{id}/like")
    public Mono<Result<Map<String, Object>>> likeAndPrecipitate(@PathVariable("id") String messageId,
                                                                @RequestBody Map<String, Object> body) {
        String conversationId = str(body.get("conversationId"));
        String content = str(body.get("content"));
        String destination = str(body.get("destination"));
        if (content == null || content.isBlank()) {
            return Mono.just(Result.error(400, "content 不能为空(需为被点赞的 assistant 回答全文)"));
        }
        log.info("消息点赞沉淀: messageId={}, conversationId={}, len={}", messageId, conversationId,
                content.length());

        // 标题取回答前若干字符
        String title = content.length() > 60 ? content.substring(0, 60) + "…" : content;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("content", content);
        payload.put("sourceType", "self_precip");
        payload.put("sourceRef", messageId);
        payload.put("destination", destination);

        return mcpClient.post().uri("/api/knowledge/documents")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(payload)
                .retrieve().bodyToMono(Map.class)
                .map(resp -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("docId", resp.get("docId"));
                    out.put("status", resp.getOrDefault("status", "pending"));
                    return Result.success(out);
                });
    }

    /** Map 取值容错转字符串 */
    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}