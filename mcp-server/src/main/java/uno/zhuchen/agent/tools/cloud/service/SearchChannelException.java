package uno.zhuchen.agent.tools.cloud.service;

import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 搜索通道故障
 *
 * <p>由各通道客户端在"基础设施层面失败"时抛出: 网络异常 / 非 2xx / 响应不可解析 /
 * API 返回业务错误码。与"调用成功但没有结果"区分开 —— 后者返回空列表, 不抛异常。
 *
 * <p>这个区分是降级判断的依据: 抛异常 = 该路故障需要弃用并记录,
 * 空列表 = 该路正常但确实没搜到。
 */
class SearchChannelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误体摘要最大长度 */
    private static final int MAX_BODY = 160;

    /**
     * @param message 故障描述, 会进日志与 ChannelStatus.error
     */
    SearchChannelException(String message) {
        super(message);
    }

    /**
     * @param message 故障描述
     * @param cause   原始异常
     */
    SearchChannelException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 把任意下游异常压成一句话, 供 ChannelStatus 与日志使用。
     *
     * <p>三个通道的压缩规则必须一致, 否则同一类故障在不同通道里表现为不同文案,
     * 排障时无法横向比较, 所以收敛到这一处。
     *
     * @param e 原始异常
     * @return 通道故障
     */
    static SearchChannelException from(Throwable e) {
        if (e instanceof WebClientResponseException wcre) {
            return new SearchChannelException(describeHttp(wcre), e);
        }
        String msg = e.getMessage();
        String text = msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
        return new SearchChannelException(text, e);
    }

    /**
     * 把 HTTP 错误码翻成一句可读的话, 并对常见错误码补提示。
     */
    private static String describeHttp(WebClientResponseException wcre) {
        int status = wcre.getStatusCode().value();
        String hint = switch (status) {
            case 401 -> "api key invalid";
            case 402 -> "balance insufficient";
            case 403 -> "rejected by policy";
            case 408 -> "request timeout";
            case 429 -> "rate limited";
            default -> status >= 500 ? "server error" : "";
        };
        String body = wcre.getResponseBodyAsString();
        String brief = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (brief.length() > MAX_BODY) {
            brief = brief.substring(0, MAX_BODY) + "...";
        }
        return "HTTP " + status + (hint.isEmpty() ? "" : " (" + hint + ")") + (brief.isEmpty() ? "" : " " + brief);
    }
}
