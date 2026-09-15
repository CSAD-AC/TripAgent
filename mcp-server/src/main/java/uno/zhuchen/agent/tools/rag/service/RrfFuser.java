package uno.zhuchen.agent.tools.rag.service;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF(Reciprocal Rank Fusion)融合器 — 合并多路召回的排序结果.
 *
 * <p>混合检索双路(向量路 + 关键词路)召回后, 用 RRF 按名次融合: fusedScore = Σ 1/(k + rank_i),
 * 其中 k 为融合常数(默认 60). 与加权分数融合不同, RRF 只依赖各路"名次"而非归一化分数,
 * 避免了不同检索器分数量纲不一致导致的分不可比问题.
 *
 * <p>线程安全: 无共享可变状态, 可静态调用.
 */
public final class RrfFuser {

    /** RRF 融合常数 k, 越大对名次差异越不敏感 */
    public static final int K = 60;

    /** 空结果常量(避免到处 new) */
    private static final List<Map.Entry<String, Double>> EMPTY = Collections.emptyList();

    private RrfFuser() {
    }

    /**
     * 融合多路按名次排序的文档 ID 列表.
     *
     * <p>每路列表中越靠前的名次越高(rank 从 1 开始). 例: 向量路 [a, b, c]、关键词路 [b, a, d]
     * 融合后 a = 1/61 + 1/62, b = 1/62 + 1/61, c = 1/63, d = 1/63。
     * 空列表或多路缺失均安全处理(只累加存在的路).
     *
     * @param rankedLists 多路召回的文档 ID 有序列表(任意路可为空)
     * @return 按融合分降序的 {@code docId -> fusedScore} 条目列表
     */
    public static List<Map.Entry<String, Double>> fuse(List<List<String>> rankedLists) {
        if (rankedLists == null || rankedLists.isEmpty()) {
            return EMPTY;
        }
        Map<String, Double> scoreMap = new HashMap<>();
        for (List<String> list : rankedLists) {
            if (list == null) {
                continue;
            }
            // rank 从 1 开始: 首位贡献 1/(k+1), 越靠后贡献越小
            for (int i = 0; i < list.size(); i++) {
                String docId = list.get(i);
                if (docId == null) {
                    continue;
                }
                scoreMap.merge(docId, 1.0 / (K + i + 1), Double::sum);
            }
        }
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(ArrayList::new,
                        (acc, e) -> acc.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue())),
                        ArrayList::addAll);
    }
}