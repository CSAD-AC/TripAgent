package uno.zhuchen.agent.tools.cloud.service;

import uno.zhuchen.agent.tools.cloud.model.SearchResult;

import java.util.List;

/**
 * 搜索通道统一接口
 *
 * <p>三个实现(百度 / DeepSeek / Tavily)的响应格式、鉴权方式、错误语义各不相同,
 * 但对外只需要一个能力: 给 query 返回结果列表。编排、融合、降级由
 * {@link CloudSearchService} 统一负责, 通道本身不关心自己是否被并行调用。
 *
 * <p>实现约定:
 * <ul>
 *   <li>确实没搜到 → 返回空列表</li>
 *   <li>基础设施失败(网络/非 2xx/解析失败/业务错误码) → 抛 {@link SearchChannelException}</li>
 * </ul>
 * 这个区分让编排层能判断"该路故障需弃用"还是"该路正常但无结果"。
 */
interface SearchClient {

    /**
     * 执行一次搜索。
     *
     * @param query 搜索词
     * @param limit 期望返回条数
     * @return 结果列表, 可能为空
     * @throws SearchChannelException 通道故障
     */
    List<SearchResult> search(String query, int limit);
}
