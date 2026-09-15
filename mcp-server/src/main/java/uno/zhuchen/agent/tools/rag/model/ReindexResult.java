package uno.zhuchen.agent.tools.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 全量重建结果 — {@code ReindexService} 的返回结构.
 *
 * <p>字段即序列化 key: {@code status / total / success / failed / message}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReindexResult {

    /** 状态: done(完成) / busy(已有重建在执行) */
    private String status;

    /** 处理的分块总数 */
    private int total;

    /** 成功向量化数量 */
    private int success;

    /** 失败数量 */
    private int failed;

    /** busy 时的提示(可选) */
    private String message;

    /** 重建完成结果 */
    public static ReindexResult done(int total, int success, int failed) {
        return new ReindexResult("done", total, success, failed, null);
    }

    /** 冲突拒绝结果(已有重建进行中) */
    public static ReindexResult busy() {
        return new ReindexResult("busy", 0, 0, 0, "reindex 已在执行, 请勿重复触发");
    }
}