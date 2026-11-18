package uno.zhuchen.agent.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import uno.zhuchen.agent.common.Result;
import uno.zhuchen.agent.config.McpClientProperties;
import uno.zhuchen.agent.core.knowledge.KnowledgeDistillService;
import uno.zhuchen.agent.core.memory.ChatMemory;
import uno.zhuchen.agent.domain.dto.KnowledgeUploadRequest;
import uno.zhuchen.agent.domain.dto.MessageLikeRequest;
import uno.zhuchen.agent.domain.vo.UploadedDocVO;
import uno.zhuchen.agent.security.UserPrincipal;

import jakarta.validation.Valid;

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

    /** 单次点赞沉淀内容长度上限(字符数), 超长截断前段入库 */
    private static final int MAX_PRECIP_CONTENT = 20_000;

    private final WebClient mcpClient;
    private final ChatMemory chatMemory;
    private final KnowledgeDistillService knowledgeDistillService;

    /**
     * 构造注入 mcp-server 连接配置并建立沉淀推送 WebClient.
     *
     * @param mcp mcp-server 连接配置(取 server-url 作为基础地址)
     */
    public MessageController(McpClientProperties mcp, ChatMemory chatMemory,
                             KnowledgeDistillService knowledgeDistillService) {
        this.mcpClient = WebClient.builder().baseUrl(mcp.getServerUrl()).build();
        this.chatMemory = chatMemory;
        this.knowledgeDistillService = knowledgeDistillService;
    }

    /**
     * 点赞消息并沉淀进知识库.
     *
     * <p>入参走 {@link MessageLikeRequest} + {@link Valid} 强类型校验, 与项目接口风格一致.
     *
     * @param messageId 消息主键(作为知识 sourceRef 溯源)
     * @param req       {conversationId, content, destination?}, 其中 content 为被点赞的 assistant 回答全文
     * @return Result&lt;UploadedDocVO&gt;, 成功携带 {docId, status}; 下游异常返回友好错误
     */
    @PostMapping("/{id}/like")
    public Mono<Result<UploadedDocVO>> likeAndPrecipitate(@PathVariable("id") String messageId,
                                                          @Valid @RequestBody MessageLikeRequest req,
                                                          @AuthenticationPrincipal UserPrincipal user) {
        return Mono.fromRunnable(() -> chatMemory.assertOwned(user.userId(), req.getConversationId()))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.defer(() -> doLikeAndPrecipitate(messageId, req)));
    }

    private Mono<Result<UploadedDocVO>> doLikeAndPrecipitate(String messageId,
                                                             MessageLikeRequest req) {
        // 先交给模型整理成知识文档(去寒暄/反问/过程叙述, 留事实与数字),
        // 模型失败时内部回退原文, 不会阻断沉淀链路
        KnowledgeDistillService.Draft draft =
                knowledgeDistillService.distill(req.getContent(), req.getDestination());
        String content = draft.content();
        // 长度保护: 超大内容截断前段入库, 防止异常上传撑爆分块
        if (content.length() > MAX_PRECIP_CONTENT) {
            log.warn("点赞沉淀内容超长, 截断入库: messageId={}, len={}, cap={}",
                    messageId, content.length(), MAX_PRECIP_CONTENT);
            content = content.substring(0, MAX_PRECIP_CONTENT) + "…";
        }
        log.info("消息点赞沉淀: messageId={}, conversationId={}, distilled={}, title={}, len={}",
                messageId, req.getConversationId(), draft.distilled(), draft.title(), content.length());

        KnowledgeUploadRequest uploadReq = KnowledgeUploadRequest.builder()
                .title(draft.title()).content(content)
                .sourceType("self_precip").sourceRef(messageId)
                .destination(req.getDestination()).build();

        return mcpClient.post().uri("/api/knowledge/documents")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(uploadReq)
                .retrieve().bodyToMono(UploadedDocVO.class)
                .map(vo -> {
                    // 回执带上整理后的标题, 前端可直接提示"已沉淀: xxx"
                    if (vo != null) {
                        vo.setTitle(draft.title());
                    }
                    return Result.success(vo);
                })
                .onErrorResume(e -> {
                    log.warn("点赞沉淀下游失败: messageId={}, error={}", messageId, e.getMessage());
                    return Mono.just(Result.error(502, "知识库服务暂不可用, 请稍后重试"));
                });
    }
}
