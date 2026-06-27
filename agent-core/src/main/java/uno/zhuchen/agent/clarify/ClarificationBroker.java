package uno.zhuchen.agent.clarify;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.domain.dto.StreamChunk;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 反问工具的"问题-回答"通道管理器
 *
 * 职责：
 * - 注册会话级 emitter：AgentController.stream() 注册一个 Consumer，用来把反问事件推入 SSE
 * - awaitAnswer：AskUserTool 调用，同步阻塞等用户回答（默认 10 分钟）
 * - submit：AgentController 收到 POST /api/chat/answer 时调用，喂入答案
 *
 * 设计要点：
 * - emitter 按 conversationId 隔离：多个会话并发不互相干扰
 * - answer 按 questionId 隔离：同一会话多次反问不互相覆盖
 * - 阻塞使用 Thread.sleep(100) 轮询，避免 Thread.suspend/resume（已废弃）
 * - 超时抛 TimeoutException，由 ReactAgent 转成"用户未回答,继续执行"注入 history
 */
@Component
@Slf4j
public class ClarificationBroker {

    /** conversationId -> SSE emitter (用于把反问事件推入流) */
    private final ConcurrentHashMap<String, Consumer<StreamChunk>> emitters = new ConcurrentHashMap<>();

    /** questionId -> 用户最终答案 */
    private final ConcurrentHashMap<String, String> answers = new ConcurrentHashMap<>();

    /** questionId -> 该问题所属的 conversationId(用于 submit 时的跨会话校验) */
    private final ConcurrentHashMap<String, String> questionOwners = new ConcurrentHashMap<>();

    @Value("${clarify.timeout-minutes:10}")
    private long timeoutMinutes;

    /**
     * 注册会话级 emitter（由 AgentController.stream() 调用）
     *
     * @param conversationId 会话 ID
     * @param emitter        SSE 推送回调（注意：调用方应保证 thread-safe）
     */
    public void registerEmitter(String conversationId, Consumer<StreamChunk> emitter) {
        emitters.put(conversationId, emitter);
        log.debug("ClarificationEmitter 注册: conversationId={}", conversationId);
    }

    /**
     * 注销 emitter（SSE 断开时调用，防止内存泄漏）
     */
    public void unregisterEmitter(String conversationId) {
        emitters.remove(conversationId);
        log.debug("ClarificationEmitter 注销: conversationId={}", conversationId);
    }

    /**
     * 提交用户答案(由 AgentController.submitAnswer 调用)
     *
     * 校验逻辑:questionId 必须属于传入的 conversationId,防止"A 会话的问题 B 会话来答"的越权
     *
     * @param conversationId 前端传来的会话 ID
     * @param questionId     前端传来的问题 ID
     * @param answer         用户回答内容
     * @throws IllegalArgumentException 当 questionId 不属于该 conversationId 时
     */
    public void submit(String conversationId, String questionId, String answer) {
        // 跨会话校验:questionId 必须由该 conversationId 发起
        String owner = questionOwners.get(questionId);
        if (owner != null && !owner.equals(conversationId)) {
            log.warn("跨会话 submit 拒绝: 声明 conversationId={}, 实际 owner={}, questionId={}",
                    conversationId, owner, questionId);
            throw new IllegalArgumentException(
                    "questionId 不属于该 conversationId: 跨会话提交被拒绝");
        }
        log.info("收到用户回答: conversationId={}, questionId={}, answer={}",
                conversationId, questionId, answer);
        answers.put(questionId, answer);
    }

    /**
     * 阻塞等待用户回答（由 AskUserTool 调用）
     *
     * 流程：
     * 1. 通过 emitter 推送反问事件到 SSE（前端弹出问题卡）
     * 2. 轮询 answers Map 等用户提交答案
     * 3. 超时抛 TimeoutException
     *
     * @param conversationId 会话 ID（用于查找 emitter）
     * @param questionId     问题 ID
     * @param req            反问事件 payload
     * @return 用户答案
     * @throws TimeoutException 超时未回答
     */
    public String awaitAnswer(String conversationId, String questionId, StreamChunk req)
            throws TimeoutException {

        // 1. 推反问事件到 SSE
        Consumer<StreamChunk> emitter = emitters.get(conversationId);
        if (emitter != null) {
            try {
                emitter.accept(req);
            } catch (Exception e) {
                log.error("推送反问事件失败: conversationId={}, questionId={}",
                        conversationId, questionId, e);
            }
        } else {
            // 没有 emitter 说明 SSE 没建好,直接超时
            log.warn("反问时未找到 SSE emitter: conversationId={}, questionId={}",
                    conversationId, questionId);
            throw new TimeoutException("SSE 连接未就绪,无法反问用户");
        }

        // 1.5 记录 questionId → conversationId 映射(供 submit 校验)
        questionOwners.put(questionId, conversationId);

        // 2. 阻塞轮询等用户回答
        long start = System.currentTimeMillis();
        long timeoutMs = Duration.ofMinutes(timeoutMinutes).toMillis();
        long deadline = start + timeoutMs;
        log.info("阻塞等用户回答: questionId={}, timeout={}min", questionId, timeoutMinutes);

        while (System.currentTimeMillis() < deadline) {
            String ans = answers.get(questionId);
            if (ans != null) {
                answers.remove(questionId);
                questionOwners.remove(questionId);   // 清理 owner 映射
                log.info("收到答案: questionId={}, 耗时 {}ms", questionId,
                        System.currentTimeMillis() - start);
                return ans;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("反问等待被中断", e);
            }
        }

        // 超时也清理 owner 映射
        questionOwners.remove(questionId);
        log.warn("反问超时未答: questionId={}, 超时 {}min", questionId, timeoutMinutes);
        throw new TimeoutException("用户未在 " + timeoutMinutes + " 分钟内回答");
    }
}
