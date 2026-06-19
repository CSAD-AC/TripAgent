package uno.zhuchen.agent.tool;

/**
 * 工具回调接口 — 预留扩展点
 *
 * 后续为 ReactAgent 注册工具时实现此接口。
 * 当前阶段（无工具）仅保留定义，供架构预览。
 *
 * 使用示例（后续）:
 *   ToolCallback weatherTool = () -> {
 *       return new ToolResponse("天气查询结果...");
 *   };
 *   reactAgent.registerTool("get_weather", weatherTool);
 */
public interface ToolCallback {

    /**
     * 工具名称（在 LLM tool call 中标识）
     */
    String getName();

    /**
     * 工具描述（LLM 据此决定是否调用）
     */
    String getDescription();

    /**
     * 执行工具
     *
     * @param arguments JSON 格式的工具参数
     * @return 工具执行结果文本
     */
    String execute(String arguments);
}
