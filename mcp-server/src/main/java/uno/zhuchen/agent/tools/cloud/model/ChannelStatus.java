package uno.zhuchen.agent.tools.cloud.model;

/**
 * 单个搜索通道的执行结果摘要
 *
 * <p>用于把"哪一路成功/失败/超时"这件事传递给调用方, 使降级可观测。
 * 通道失败不影响整体搜索可用性, 只贡献一条状态记录。
 *
 * @param name      通道名: baidu / deepseek / tavily
 * @param hits      该通道返回的结果条数(失败时为 0)
 * @param latencyMs 该通道耗时(毫秒)
 * @param error     失败原因, null 表示成功(条数为 0 也算成功, 即确实没有结果)
 */
public record ChannelStatus(String name, int hits, long latencyMs, String error) {

    /**
     * 通道是否可用(未抛错)。
     *
     * @return true 表示该通道正常执行完毕
     */
    public boolean ok() {
        return error == null;
    }

    /**
     * 通道是否贡献了结果。
     *
     * @return true 表示既没出错又至少返回 1 条
     */
    public boolean contributed() {
        return error == null && hits > 0;
    }
}
