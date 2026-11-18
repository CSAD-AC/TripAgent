package uno.zhuchen.agent.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档上传/点赞沉淀结果 VO — 请求入库成功后返回的轻量回执.
 *
 * <p>对应 mcp-server 上传接口返回的 {docId, status} JSON 契约.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadedDocVO {

    /** 入库文档主键 */
    private String docId;

    /** 处理状态(pending 等), 可轮询详情查看最终状态 */
    private String status;

    /**
     * 文档标题(仅点赞沉淀返回:
     * 上游 mcp-server 不返回该字段, 由 agent-core 在整理后回填, 便于前端提示)
     */
    private String title;
}
