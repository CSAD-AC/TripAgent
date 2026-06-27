package uno.zhuchen.workflow.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 路线规划结果 — RouteAgent 输出
 *
 * 一个完整的旅行通常有去程 + 返程 + 市内交通多段，
 * 故 RouteResult 是路线方案列表，不是单段路线。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteResult {

    /**
     * 路线方案（如 "train", "flight", "self-drive"）
     * 多段路线时按顺序填
     */
    @Builder.Default
    private List<RouteSegment> segments = new ArrayList<>();

    /** 总费用（人民币元） */
    private Integer totalCost;

    /** 总时长（分钟） */
    private Integer totalDurationMin;

    /**
     * 路线摘要（用于报告 Agent 拼接 Markdown）
     */
    private String summary;

    /**
     * 路线段 — 单段交通方案
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RouteSegment {

        /** 交通方式: train / flight / self-drive / bus */
        private String mode;

        /** 出发地 */
        private String from;

        /** 目的地 */
        private String to;

        /** 费用（元） */
        private Integer cost;

        /** 时长（分钟） */
        private Integer durationMin;

        /** 补充说明（如 "高铁 G1234", "经济舱"） */
        private String description;
    }
}
