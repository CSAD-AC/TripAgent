package uno.zhuchen.agent.core.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uno.zhuchen.agent.config.ModelRouter;
import uno.zhuchen.agent.core.llm.ChatModel;

import java.time.LocalDate;
import java.util.List;

/**
 * 知识沉淀整理器 — 把一段助手回答整理成适合入库的知识文档。
 *
 * <p>背景: 早前点赞沉淀直接把回答全文塞进知识库, 结果库里全是"我查了一下…"
 * 这类对话口吻、含寒暄与工具调用叙述的文本, 分块后语义检索质量很差,
 * 而且每次沉淀都会把同样的客套话重复写一遍。
 *
 * <p>职责: 交给模型按固定骨架重写成结构化 Markdown(标题/摘要/概览/行程或结论/
 * 可复用要点/标签), 去掉寒暄、反问、指代与过程叙述, 保留事实、数字、金额、地点。
 *
 * <p>工程约束:
 * <ul>
 *   <li>开关 {@code app.knowledge.distill.enabled} 默认开启; 关闭后行为退回原文沉淀</li>
 *   <li>模型失败/超时/输出不合规一律回退原文, 不让沉淀链路中断</li>
 *   <li>输入过短(不值得整理)直接原文沉淀, 省一次模型调用</li>
 * </ul>
 */
@Slf4j
@Service
public class KnowledgeDistillService {

    /** 低于该长度的回答不值得花一次模型调用(沉淀通常是方案级回答, 60 字符已能承载结论) */
    private static final int MIN_INPUT_CHARS = 60;

    /** 送模型的正文上限, 防止超长回答撑爆上下文 */
    private static final int MAX_INPUT_CHARS = 12_000;

    /** 整理结果低于该长度视为无效(模型偷懒), 回退原文 */
    private static final int MIN_OUTPUT_CHARS = 80;

    private static final int MAX_TITLE_CHARS = 60;

    private static final String SYSTEM_PROMPT = """
            你是旅行知识库的整理员。输入是旅行助手对用户的一次回答, 输出是一篇可复用的知识文档。

            整理要求:
            - 去掉对话口吻: "我查了一下""根据你的要求""要不要我再…"、反问、寒暄、工具调用过程全部删掉
            - 保留全部事实性信息: 目的地、天数、人数、预算金额、交通方式、时间、门票、地址、注意事项
            - 把指代补全("你"→"用户", "这里"→具体地名), 让文档脱离上下文也能读懂
            - 不要编造输入里没有的信息; 缺失的关键项写"未提及"
            - 只输出 Markdown 正文, 不要输出解释、不要用 ``` 代码块包裹

            输出骨架(严格按此顺序, 无内容的小节可省略):
            # 标题(不超过 25 字, 含目的地与主题, 例如"京都五日游行程方案")

            > 一句话摘要(不超过 60 字)

            ## 概览
            - 目的地:
            - 天数:
            - 人数/同行人:
            - 预算:
            - 交通:

            ## 行程与安排
            (按天或按主题列出, 保留时间、地点、费用)

            ## 可复用要点
            (住哪一带更合适、哪些坑要避开、怎么省钱这类下次还能用的结论)

            ## 标签
            (3~6 个关键词, 逗号分隔)
            """;

    private final ModelRouter modelRouter;
    private final boolean enabled;
    private final String modelId;

    public KnowledgeDistillService(ModelRouter modelRouter,
                                   @Value("${app.knowledge.distill.enabled:true}") boolean enabled,
                                   @Value("${app.knowledge.distill.model:}") String modelId) {
        this.modelRouter = modelRouter;
        this.enabled = enabled;
        this.modelId = modelId;
    }

    /**
     * 整理一段回答。
     *
     * @param answer      助手回答全文
     * @param destination 目的地(可选, 帮助模型定标题)
     * @return 整理后的文档草稿; {@code distilled=false} 表示走的原文回退
     */
    public Draft distill(String answer, String destination) {
        String text = answer == null ? "" : answer.trim();
        if (text.isEmpty()) {
            return new Draft(fallbackTitle(destination), "", false);
        }
        if (!enabled || text.length() < MIN_INPUT_CHARS) {
            return rawDraft(text, destination);
        }
        String input = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
        try {
            List<Message> messages = List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(buildUserMessage(input, destination)));
            ChatModel model = modelRouter.route(modelId);
            AssistantMessage reply = model.call(messages, modelRouter.resolveModelName(modelId));
            String output = reply == null ? null : clean(reply.getText());
            if (output == null || output.length() < MIN_OUTPUT_CHARS) {
                throw new IllegalStateException("整理结果过短或为空");
            }
            String title = extractTitle(output, destination);
            log.info("[knowledge-distill] 整理完成: title={}, inLen={}, outLen={}",
                    title, text.length(), output.length());
            return new Draft(title, output, true);
        } catch (Exception e) {
            log.warn("[knowledge-distill] 整理失败, 回退原文沉淀: error={}", e.getMessage());
            return rawDraft(text, destination);
        }
    }

    private String buildUserMessage(String content, String destination) {
        StringBuilder builder = new StringBuilder();
        if (destination != null && !destination.isBlank()) {
            builder.append("目的地提示: ").append(destination.trim()).append("\n\n");
        }
        builder.append("待整理的回答:\n").append(content);
        return builder.toString();
    }

    /** 去掉模型可能加上的代码块围栏与前后多余空行 */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        return text.isEmpty() ? null : text;
    }

    /** 取一级标题; 没有就退到首个非空行, 再退到"目的地+日期" */
    private static String extractTitle(String content, String destination) {
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                String title = stripMarkdown(trimmed);
                if (isUsableTitle(title)) {
                    return truncate(title);
                }
                continue;
            }
            // 列表项(如 "- **巴士一日券**：700 日元")是正文, 不能拿来当文档标题
            if (trimmed.matches("^[-*+·]\\s+.*")) {
                continue;
            }
            // 按出现顺序取第一个"像标题"的行; 标签行(整理后的回答：)跳过继续看下一行
            String plain = stripMarkdown(trimmed);
            if (isUsableTitle(plain)) {
                return truncate(plain);
            }
        }
        return fallbackTitle(destination);
    }

    /** 标题可用性: 过滤掉"整理后的回答："这类模型自说自话的标签行 */
    private static boolean isUsableTitle(String title) {
        if (title == null) {
            return false;
        }
        String text = title.trim();
        if (text.length() < 2 || text.endsWith("：") || text.endsWith(":")) {
            return false;
        }
        return !text.matches("^(整理后的回答|整理结果|整理后|整理|输出|结果|正文|知识文档|文档|内容)$");
    }

    /** 去掉模型可能带上的 #/列表符/加粗标记, 标题要能直接当文档名用 */
    private static String stripMarkdown(String raw) {
        // 顺序很关键: 先剥成对的加粗标记, 再剥标题/列表前缀。
        // 反过来 "**标题**" 会被列表符规则吃掉一个 *, 变成 "*标题**"。
        String text = raw.trim()
                .replaceAll("^\\*\\*(.*)\\*\\*$", "$1")
                .replaceAll("^__(.*)__$", "$1")
                .trim();
        text = text.replace("**", "").replace("__", "");
        text = text.replaceFirst("^#+\\s*", "")
                .replaceFirst("^[*+-]\\s+", "")
                .replaceFirst("^>\\s*", "")
                .trim();
        return text;
    }

    private static String fallbackTitle(String destination) {
        String prefix = destination == null || destination.isBlank() ? "旅行" : destination.trim();
        return prefix + "知识沉淀 " + LocalDate.now();
    }

    private static String truncate(String title) {
        return title.length() > MAX_TITLE_CHARS ? title.substring(0, MAX_TITLE_CHARS) + "…" : title;
    }

    /**
     * 原文回退: 标题取首句(够短就用), 否则"目的地 + 日期", 避免把整段回答前半截当标题。
     */
    private Draft rawDraft(String text, String destination) {
        String title = firstSentence(text);
        if (title.length() < 8 || title.length() > MAX_TITLE_CHARS) {
            title = fallbackTitle(destination);
        }
        return new Draft(title, text, false);
    }

    private static String firstSentence(String text) {
        String[] parts = text.split("[。！？\\n]");
        String first = parts.length > 0 ? parts[0].trim() : text.trim();
        first = first.replaceAll("\\s+", " ");
        return first.length() > MAX_TITLE_CHARS ? first.substring(0, MAX_TITLE_CHARS) + "…" : first;
    }

    /** 整理结果(标题 + Markdown 正文) */
    public record Draft(String title, String content, boolean distilled) {
    }
}
