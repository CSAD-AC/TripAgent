package uno.zhuchen.agent.tools.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档上传/沉淀结果响应 — 提交入库后立即返回给调用方的结果.
 *
 * <p>字段即序列化 key: {@code docId / status(pending)}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UploadDocResult {

    /** 新建文档主键(供后续轮询状态) */
    private String docId;

    /** 初始索引状态: 通常为 pending */
    private String status;
}