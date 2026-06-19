package uno.zhuchen.agent.prompt;

/**
 * 提示词模板 — 支持 {placeholder} 替换
 *
 * <p>用途：构建带变量的 prompt，如根据用户角色、上下文动态生成 system prompt。</p>
 */
public class PromptTemplate {

    private final String template;

    public PromptTemplate(String template) {
        this.template = template;
    }

    /**
     * 替换模板中的占位符
     *
     * @param key   占位符名称（不含花括号）
     * @param value 替换值
     */
    public PromptTemplate bind(String key, String value) {
        String resolved = template.replace("{" + key + "}", value != null ? value : "");
        return new PromptTemplate(resolved);
    }

    /**
     * 获取渲染后的文本
     */
    public String render() {
        return template;
    }
}
