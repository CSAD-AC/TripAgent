package uno.zhuchen.agent.tools.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合器单元测试.
 *
 * <p>覆盖: 双路相同结果合分 / 单路缺失 / 空候选 / 名次敏感性与并列稳定性.
 */
class RrfFuserTest {

    /** 双路相同结果时, 出现在两路的 doc 融合分 > 只在一路的 doc */
    @Test
    void fuse_twoRanksCombineScore() {
        List<List<String>> ranked = List.of(
                List.of("a", "b", "c"),
                List.of("b", "c", "d")
        );
        List<Map.Entry<String, Double>> fused = RrfFuser.fuse(ranked);
        // a 仅向量路 rank1; b 双路 rank2+v路rank1
        Map<String, Double> score = toMap(fused);
        assertTrue(score.get("b") > score.get("a"), "双路命中的 b 应高于单路命中的 a");
        assertTrue(score.get("a") > score.get("d"), "rank1 的 a 应高于 rank3 的 d");
        // b = 1/61 + 1/62(向量路 b 是 rank2)
        double expectB = 1.0 / (RrfFuser.K + 1) + 1.0 / (RrfFuser.K + 2);
        assertEquals(expectB, score.get("b"), 1e-9);
    }

    /** 单路缺失(null 或空列表)时按剩余路融合, 不抛异常 */
    @Test
    void fuse_missingRankTolerated() {
        // 用 Arrays.asList 以允许 null 元素(List.of 不允许)
        List<List<String>> ranked = java.util.Arrays.<List<String>>asList(
                java.util.Arrays.asList("a", "b"), null, List.of()
        );
        List<Map.Entry<String, Double>> fused = RrfFuser.fuse(ranked);
        Map<String, Double> score = toMap(fused);
        assertEquals(2, fused.size(), "只应包含有数据的路");
        assertEquals(1.0 / (RrfFuser.K + 1), score.get("a"), 1e-9);
    }

    /** 空候选(各路全空或直接 null 输入)返回空列表 */
    @Test
    void fuse_emptyCandidatesReturnsEmpty() {
        assertTrue(RrfFuser.fuse(List.of()).isEmpty());
        assertTrue(RrfFuser.fuse(null).isEmpty());
        List<List<String>> allEmpty = List.of(List.of(), List.of());
        assertTrue(RrfFuser.fuse(allEmpty).isEmpty());
    }

    /** 融合结果按分数降序, 同分时保持字典序稳定 */
    @Test
    void fuse_sortedByScoreDesc() {
        List<List<String>> ranked = List.of(
                List.of("x", "y"),
                List.of("y", "x")
        );
        List<Map.Entry<String, Double>> fused = RrfFuser.fuse(ranked);
        Map<String, Double> score = toMap(fused);
        assertEquals(score.get("x"), score.get("y"), 1e-9);
        // 同分按字典序: x 在前
        assertEquals(List.of("x", "y"), fused.stream().map(Map.Entry::getKey).toList());
    }

    /** 辅助: 条目列表转 Map */
    private static Map<String, Double> toMap(List<Map.Entry<String, Double>> entries) {
        return entries.stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}