package uno.zhuchen.agent.common;


import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {


    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage());
        log.error("系统异常堆栈信息:\n{}", getStackTrace(e));
        return Result.error(500, "系统异常");
    }

    /**
     * 参数非法(由 conversationId 格式校验、submit 跨会话校验等触发)
     *
     * 返回 400 而不是 500,因为这是客户端请求问题
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数非法: {}", e.getMessage());
        return Result.error(400, e.getMessage());
    }

    /**
     * 获取异常堆栈信息
     */
    private String getStackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
}
