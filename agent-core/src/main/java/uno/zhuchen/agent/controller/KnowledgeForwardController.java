package uno.zhuchen.agent.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 知识库管理转发层 — 对外统一暴露 8080 入口, 透传到 mcp-server(:8081) 的文档管理 REST.
 *
 * <p>代理语义: 本控制器只做请求转发与响应原样回传, 保持 8080 为唯一对外 API 入口,
 * 便于未来在此统一鉴权/限流(对应 {@code RAG_PLAN.md} 的 agent-core 转发层设计).
 *
 * <p>均为异步透传: 不出现在 Netty event loop 上执行阻塞逻辑.
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeForwardController {

    /** 指向 mcp-server 文档管理服务 */
    private final WebClient mcpClient;

    /**
     * 构造注入 mcp-server 基础地址并建立转发 WebClient.
     *
     * @param mcpServerUrl mcp-server 地址, 默认 http://localhost:8081
     */
    public KnowledgeForwardController(@Value("${mcp.server-url:http://localhost:8081}") String mcpServerUrl) {
        this.mcpClient = WebClient.builder().baseUrl(mcpServerUrl).build();
    }

    /**
     * 上传文档入库(异步). 透传 mcp-server, 返回 {docId, status}.
     *
     * @param body {title, content, destination?, theme?, sourceRef?, sourceType?}
     * @return mcp-server 响应体 JSON
     */
    @PostMapping(value = "/documents", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> upload(@RequestBody String body) {
        log.info("知识库转发: 上传文档");
        return mcpClient.post().uri("/api/knowledge/documents").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(String.class);
    }

    /**
     * 文档分页列表(排除已删除).
     *
     * @param page 页码
     * @param size 每页条数
     * @return {total, items}
     */
    @GetMapping(value = "/documents", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> list(@RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "20") int size) {
        log.info("知识库转发: 文档列表 page={}, size={}", page, size);
        return mcpClient.get().uri(uri -> uri.path("/api/knowledge/documents")
                        .queryParam("page", page).queryParam("size", size).build())
                .retrieve().bodyToMono(String.class);
    }

    /**
     * 文档详情 + 索引状态(上传后轮询用).
     *
     * @param id 文档主键
     * @return 文档实体 JSON
     */
    @GetMapping(value = "/documents/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> detail(@PathVariable String id) {
        log.info("知识库转发: 文档详情 id={}", id);
        return mcpClient.get().uri("/api/knowledge/documents/{id}", id)
                .retrieve().bodyToMono(String.class);
    }

    /**
     * 删除文档(软删 doc + 物理删分块与向量).
     *
     * @param id 文档主键
     * @return mcp-server 响应体
     */
    @DeleteMapping(value = "/documents/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> delete(@PathVariable String id) {
        log.info("知识库转发: 删除文档 id={}", id);
        return mcpClient.delete().uri("/api/knowledge/documents/{id}", id)
                .retrieve().bodyToMono(String.class);
    }

    /**
     * 知识库统计.
     *
     * @return 统计 JSON(文档数/分块数/索引状态)
     */
    @GetMapping(value = "/documents/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> stats() {
        log.info("知识库转发: 统计");
        return mcpClient.get().uri("/api/knowledge/documents/stats")
                .retrieve().bodyToMono(String.class);
    }
}