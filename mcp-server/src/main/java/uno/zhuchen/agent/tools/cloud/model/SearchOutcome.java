package uno.zhuchen.agent.tools.cloud.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 一次搜索的完整结果
 *
 * <p>包含融合后的结果列表与逐通道状态。调用方既能拿到结果, 也能知道
 * "是否有通道故障被降级掉", 避免通道静默失败导致结果质量莫名其妙地变差。
 *
 * @param results  融合后按相关度排序的结果, 可能为空
 * @param channels 本次参与执行的通道状态, 顺序与提交顺序一致
 */
public record SearchOutcome(List<SearchResult> results, List<ChannelStatus> channels) {

    /** 空结果常量, 避免到处 new */
    private static final SearchOutcome EMPTY = new SearchOutcome(List.of(), List.of());

    /**
     * 空结果(未提交任何通道)。
     *
     * @return 空 outcome
     */
    public static SearchOutcome empty() {
        return EMPTY;
    }

    /**
     * 是否发生了降级: 至少一路故障, 或至少一路零结果。
     *
     * <p>注意"零结果"也算降级信号: 电商软文农场把某一路刷满时, 只靠另一路补位
     * 这件事本身值得记录。
     *
     * @return true 表示本次结果不是所有通道都正常且有产出
     */
    public boolean degraded() {
        return channels.stream().anyMatch(c -> !c.contributed());
    }

    /**
     * 贡献了结果的通道名, 逗号分隔, 用于日志与工具输出。
     *
     * @return 例如 "baidu,deepseek"; 全部失败时返回空串
     */
    public String contributedChannels() {
        return channels.stream()
                .filter(ChannelStatus::contributed)
                .map(ChannelStatus::name)
                .collect(Collectors.joining(","));
    }

    /**
     * 失败通道的简述, 用于把故障原因透给上层。
     *
     * @return 例如 "deepseek:timeout"; 无故障时返回空串
     */
    public String failures() {
        return channels.stream()
                .filter(c -> !c.ok())
                .map(c -> c.name() + ":" + c.error())
                .collect(Collectors.joining("; "));
    }
}
