package uno.zhuchen.workflow.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单日行程 — ItineraryAgent 输出
 *
 * 完整行程是 List<DayPlan>，按 dayIndex 排序。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DayPlan {

    /** 第几天（从 1 开始） */
    private Integer dayIndex;

    /** 日期（如 "2024-05-01"，可空） */
    private String date;

    /** 当日景点/活动列表 */
    @Builder.Default
    private List<PoiActivity> pois = new ArrayList<>();

    /** 当日天气摘要 */
    private String weather;

    /** 当日餐饮建议 */
    private String dining;

    /** 当日住宿建议 */
    private String accommodation;

    /**
     * 单个景点/活动
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PoiActivity {

        /** 景点/活动名称 */
        private String name;

        /** 类型: attraction / restaurant / hotel / transport / activity */
        private String type;

        /** 预计停留时长（分钟） */
        private Integer durationMin;

        /** 预计费用（人民币元） */
        private Integer cost;

        /** 备注（如 "需提前预约", "周一闭馆"） */
        private String note;
    }
}
