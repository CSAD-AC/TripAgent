package uno.zhuchen.agent.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.config.ModelRouter;
import uno.zhuchen.agent.core.llm.ChatModel;
import uno.zhuchen.agent.domain.entity.MessageEntity;
import uno.zhuchen.agent.persistence.mapper.ConversationMapper;
import uno.zhuchen.agent.persistence.mapper.MessageMapper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话历史压缩器 — 滑动窗口 + LLM 旧消息摘要.
 *
 * <p>长会话消息无限增长会让 token 成本线性上涨. 本组件在消息数超过窗口阈值时,
 * 用 LLM 把最旧一段对话压缩成摘要, 写入 {@code conversation.summary},
 * 并物理删除已压缩的旧消息行, 从而既压缩存储又压缩后续请求的 token 量.
 *
 * <p>加载侧由 {@link PersistentChatMemory} 配合: 超窗口时只返回最近窗口消息,
 * 并把摘要以 SystemMessage 前缀注入, 保持上下文连续.
 *
 * <p>工程约束:
 * <ul>
 *   <li>开关: {@code app.memory.window-size}(默认 0=关闭, 关闭时行为与原来一致)</li>
 *   <li>异步: 压缩在独立线程池执行, 不阻塞请求; 失败仅记日志</li>
 *   <li>协议安全: 裁剪边界回退到非 tool 消息, 避免切断 assistant(tool_calls)+tool 成对消息</li>
 * </ul>
 */
@Slf4j
@Component
public class HistoryCompactor {

    /** 最近保留的完整消息条数; 0 = 关闭压缩 */
    private final int windowSize;

    private final ModelRouter modelRouter;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    /** 压缩专用单线程池(串行避免并发写 summary 竞态) */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-compact");
        t.setDaemon(true);
        return t;
    });

    public HistoryCompactor(ModelRouter modelRouter,
                            ConversationMapper conversationMapper,
                            MessageMapper messageMapper,
                            @Value("${app.memory.window-size:0}") int windowSize) {
        this.modelRouter = modelRouter;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.windowSize = windowSize;
    }

    /** 压缩是否启用 */
    public boolean enabled() {
        return windowSize > 0;
    }

    /** 窗口大小 */
    public int windowSize() {
        return windowSize;
    }

    /**
     * 异步触发一次压缩: 若消息数超过窗口则摘要最旧段并删除旧行.
     *
     * <p>发在独立线程池, 不阻塞调用方; 内部自带条数判断与异常兜底.
     *
     * @param conversationId 会话 ID
     */
    public void condenseAsync(String conversationId) {
        if (!enabled() || conversationId == null || conversationId.isBlank()) {
            return;
        }
        executor.submit(() -> {
            try {
                doCondense(conversationId);
            } catch (Exception e) {
                log.warn("[memory-compact] 对话历史压缩失败: conversationId={}, error={}",
                        conversationId, e.getMessage());
            }
        });
    }

    /**
     * 执行压缩: 读全量消息 → 超窗口则摘最旧段生成摘要 → 更新 summary → 删除旧行.
     */
    private void doCondense(String conversationId) {
        List<MessageEntity> entities = messageMapper.listByConversation(conversationId);
        if (entities.isEmpty()) {
            return;
        }
        int keep = Math.min(windowSize, entities.size());
        if (keep >= entities.size()) {
            return; // 未超窗口, 无需压缩
        }

        // 边界安全: 裁剪起始位置回退, 避免窗口以孤立的 tool 消息开头(其 assistant 前缀在 head)
        int start = Math.max(0, entities.size() - keep);
        while (start > 0 && "tool".equals(entities.get(start).getRole())) {
            start--;
        }
        List<MessageEntity> head = entities.subList(0, start);
        if (head.isEmpty()) {
            return;
        }

        // 组装 transcript 供 LLM 摘要
        ChatModel chatModel = modelRouter.route(null);
        String modelName = modelRouter.resolveModelName(null);
        StringBuilder transcript = new StringBuilder();
        for (MessageEntity e : head) {
            transcript.append(e.getRole()).append(": ")
                    .append(e.getContent() == null ? "" : e.getContent())
                    .append("\n");
        }
        List<Message> messages = List.of(
                new SystemMessage("你是对话摘要助手。把以下历史对话压缩成要点摘要, 保留关键事实、用户偏好、已确认的信息与工具结论, 用简洁的中文, 不要添加新信息。"),
                new UserMessage(transcript.toString()));

        AssistantMessage summary = chatModel.call(messages, modelName);
        String newSummary = summary != null && summary.getText() != null
                ? summary.getText().trim() : "";

        // 与既有摘要合并(旧摘要在前, 保留时间顺序)
        String existing = conversationMapper.selectSummary(conversationId);
        String merged = existing == null || existing.isBlank()
                ? newSummary
                : existing + "\n\n" + newSummary;
        if (!merged.isBlank()) {
            conversationMapper.updateSummary(conversationId, merged);
        }

        // 物理删除已压缩的旧行, 既收敛存储也避免下次重复摘要同一段
        List<Long> headIds = head.stream().map(MessageEntity::getId).toList();
        messageMapper.delete(new QueryWrapper<MessageEntity>().in("id", headIds));
        log.info("[memory-compact] 对话历史压缩完成: conversationId={}, condensed={}, kept={}, mergedSummaryLen={}",
                conversationId, head.size(), entities.size() - head.size(), merged.length());
    }
}