package uno.zhuchen.agent.tools.cloud.model;

/**
 * 搜索结果的统一视图
 *
 * <p>字段口径按通道取并集, 通道没给的填 null:
 * <ul>
 *   <li><b>百度</b>: date / authority_score / rerank_score / website 全部可用,
 *       但 rerank_score 实测恒为 1.0(无区分力), authority_score 实测 0.5~1.0</li>
 *   <li><b>DeepSeek</b>: date(RFC3339) / authority(0~4) / rerank_score(0~3, 有真实梯度) /
 *       site_name 全部可用; snippet 优先取长摘要 summary(600~800 字), 信息量远高于短摘要</li>
 *   <li><b>Tavily</b>: 仅 score(0~1 相关度) 可用, 其余为 null</li>
 * </ul>
 *
 * <p><b>注意</b>: authority 与 rerankScore 的量纲在不同通道不同, 不可跨通道直接比较。
 * 跨通融合必须按名次融合(见 {@code RrfFuser}), 不能按分数排序。
 *
 * @param title       结果标题
 * @param url         结果链接, 跨通道去重的主键
 * @param snippet     摘要
 * @param siteName    站点名
 * @param date        发布时间, 已归一化为 yyyy-MM-dd
 * @param authority   权威性分数(量纲随通道变化)
 * @param rerankScore 排序分数(量纲随通道变化)
 */
public record SearchResult(String title, String url, String snippet,
                           String siteName, String date,
                           Double authority, Double rerankScore) {

    /**
     * 便捷构造: 只有标题/链接/摘要, 元数据全为 null。
     *
     * @param title   标题
     * @param url     链接
     * @param snippet 摘要
     */
    public SearchResult(String title, String url, String snippet) {
        this(title, url, snippet, null, null, null, null);
    }
}
