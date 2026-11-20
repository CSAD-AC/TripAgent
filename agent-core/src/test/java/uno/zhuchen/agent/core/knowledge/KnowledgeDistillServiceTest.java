package uno.zhuchen.agent.core.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import uno.zhuchen.agent.config.ModelRouter;
import uno.zhuchen.agent.core.llm.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeDistillServiceTest {

    /** 足够长、会被送去整理的回答 */
    private static final String LONG_ANSWER =
            "我查了一下手头的记录，京都住宿建议住四条河原町或乌丸御池一带，"
                    + "步行可达清水寺、八坂神社和锦市场，晚上吃饭也方便，"
                    + "如果要去奈良大阪，住京都站更省时间。预算按每天房费 600 元估算，"
                    + "五天四晚合计 2400 元。想让我按这个预算再排一版行程吗？";

    @Test
    void disabledFallsBackToRawContent() {
        ModelRouter router = mock(ModelRouter.class);
        KnowledgeDistillService service = new KnowledgeDistillService(router, false, "");

        KnowledgeDistillService.Draft draft = service.distill(LONG_ANSWER, "京都");

        assertFalse(draft.distilled());
        assertEquals(LONG_ANSWER, draft.content());
    }

    @Test
    void shortAnswerSkipsModelCall() {
        ModelRouter router = mock(ModelRouter.class);
        KnowledgeDistillService service = new KnowledgeDistillService(router, true, "");

        KnowledgeDistillService.Draft draft = service.distill("住四条河原町比较方便。", "京都");

        assertFalse(draft.distilled());
        assertTrue(draft.content().contains("四条河原町"));
    }

    @Test
    void usesModelOutputAndExtractedTitle() {
        ModelRouter router = mock(ModelRouter.class);
        when(router.route(any())).thenReturn(fixedModel(DISTILLED));
        when(router.resolveModelName(any())).thenReturn("test-model");
        KnowledgeDistillService service = new KnowledgeDistillService(router, true, "");

        KnowledgeDistillService.Draft draft = service.distill(LONG_ANSWER, "京都");

        assertTrue(draft.distilled());
        assertEquals("京都住宿选择方案", draft.title());
        assertTrue(draft.content().startsWith("# 京都住宿选择方案"));
    }

    @Test
    void modelFailureFallsBackToRawContent() {
        ModelRouter router = mock(ModelRouter.class);
        when(router.route(any())).thenReturn(brokenModel());
        when(router.resolveModelName(any())).thenReturn("test-model");
        KnowledgeDistillService service = new KnowledgeDistillService(router, true, "");

        KnowledgeDistillService.Draft draft = service.distill(LONG_ANSWER, "京都");

        assertFalse(draft.distilled());
        assertEquals(LONG_ANSWER, draft.content());
    }

    /** 模型偶尔把标题写成 **加粗**, 落库前必须剥掉标记 */
    @Test
    void titleStripsMarkdownEmphasis() {
        ModelRouter router = mock(ModelRouter.class);
        when(router.route(any())).thenReturn(fixedModel(
                "**京都三日游整理清单**\n\n> 三天两晚, 住四条河原町, 预算 5000 元以内。\n\n"
                        + "## 概览\n- 目的地: 京都\n- 天数: 3 天\n\n"
                        + "## 可复用要点\n伏见稻荷要早去, 八点前人最少。"));
        when(router.resolveModelName(any())).thenReturn("test-model");
        KnowledgeDistillService service = new KnowledgeDistillService(router, true, "");

        KnowledgeDistillService.Draft draft = service.distill(LONG_ANSWER, "京都");

        assertEquals("京都三日游整理清单", draft.title());
    }

    /** 模型没按骨架输出时, 标题不能取到"整理后的回答："这种标签行 */
    @Test
    void titleSkipsModelLabelLines() {
        ModelRouter router = mock(ModelRouter.class);
        when(router.route(any())).thenReturn(fixedModel(
                "整理后的回答：\n\n## 京都交通方案\n京都巴士一日券 700 日元, 地铁一日券 800 日元,"
                        + "一天跑三个以上景点买巴士券更划算。岚山方向坐 JR 嵯峨野线 240 日元。"));
        when(router.resolveModelName(any())).thenReturn("test-model");
        KnowledgeDistillService service = new KnowledgeDistillService(router, true, "");

        KnowledgeDistillService.Draft draft = service.distill(LONG_ANSWER, "京都");

        assertEquals("京都交通方案", draft.title());
    }

    /** 没有 # 标题时, 列表项不能当标题, 退到"目的地 + 日期" */
    @Test
    void bulletLinesAreNotUsedAsTitle() {
        ModelRouter router = mock(ModelRouter.class);
        when(router.route(any())).thenReturn(fixedModel(
                "京都交通建议整理如下：\n\n- **巴士一日券**：成人 700 日元, 覆盖市内主要景点\n"
                        + "- **地铁一日券**：成人 800 日元, 适合东西线南北线通勤\n"
                        + "- **怎么选**：一天跑三个以上景点且主要靠巴士时买巴士券更划算, 只在祇园一带活动步行就够\n"
                        + "- **去岚山**：坐 JR 嵯峨野线, 京都站出发约 16 分钟, 票价 240 日元"));
        when(router.resolveModelName(any())).thenReturn("test-model");
        KnowledgeDistillService service = new KnowledgeDistillService(router, true, "");

        KnowledgeDistillService.Draft draft = service.distill(LONG_ANSWER, "京都");

        assertTrue(draft.title().startsWith("京都知识沉淀"), "实际标题=" + draft.title());
    }

    private static final String DISTILLED = """
            # 京都住宿选择方案

            > 按行程重心选住宿区域, 东山景点密集时优先四条河原町。

            ## 概览
            - 目的地: 京都
            - 住宿预算: 600 元/晚

            ## 可复用要点
            东山景点密集时住四条河原町, 往返奈良大阪时住京都站。
            """;

    /** 返回固定文本的假模型(不用 Mockito 匹配 varargs, 避免 stub 不生效) */
    private static ChatModel fixedModel(String text) {
        return new ChatModel() {
            @Override
            public AssistantMessage call(List<Message> messages, String modelName, ToolCallback... tools) {
                return new AssistantMessage(text);
            }

            @Override
            public Flux<ChatResponse> stream(List<Message> messages, String modelName, ToolCallback... tools) {
                return Flux.empty();
            }
        };
    }


    /** 永远失败的假模型 */
    private static ChatModel brokenModel() {
        return new ChatModel() {
            @Override
            public AssistantMessage call(List<Message> messages, String modelName, ToolCallback... tools) {
                throw new IllegalStateException("网关超时");
            }

            @Override
            public Flux<ChatResponse> stream(List<Message> messages, String modelName, ToolCallback... tools) {
                return Flux.empty();
            }
        };
    }
}

