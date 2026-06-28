package uno.zhuchen.agent.tools.ticket12306.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.tools.ticket12306.service.TicketParser;

@Component
public class CurrentDateTool {

    private final TicketParser ticketParser;

    public CurrentDateTool(TicketParser ticketParser) {
        this.ticketParser = ticketParser;
    }

    @Tool(description = "获取当前日期(北京时间, yyyy-MM-dd), 用于确定查询日期(如今天/明天/后天等)")
    public String getCurrentDate() {
        return ticketParser.getCurrentDateStr();
    }
}
