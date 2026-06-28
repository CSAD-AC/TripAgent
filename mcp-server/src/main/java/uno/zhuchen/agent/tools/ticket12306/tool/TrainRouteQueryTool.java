package uno.zhuchen.agent.tools.ticket12306.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.tools.ticket12306.model.RouteStationInfo;
import uno.zhuchen.agent.tools.ticket12306.service.Ticket12306Service;
import uno.zhuchen.agent.tools.ticket12306.service.TicketFormatter;
import uno.zhuchen.agent.tools.ticket12306.service.TicketParser;

import java.util.List;

@Component
public class TrainRouteQueryTool {

    private static final Logger log = LoggerFactory.getLogger(TrainRouteQueryTool.class);

    private final Ticket12306Service ticketService;
    private final TicketParser ticketParser;
    private final TicketFormatter ticketFormatter;

    public TrainRouteQueryTool(Ticket12306Service ticketService,
                               TicketParser ticketParser,
                               TicketFormatter ticketFormatter) {
        this.ticketService = ticketService;
        this.ticketParser = ticketParser;
        this.ticketFormatter = ticketFormatter;
    }

    @Tool(description = "查询指定列车在指定日期的完整经停站信息, 包括车次、车站、到达/出发时间、历时。"
            + "日期格式 yyyy-MM-dd, 如: 2026-06-28。"
            + "format支持: text(表格文本) json(JSON格式)。")
    public String getTrainRouteStations(
            @ToolParam(description = "列车车次, 如: G1033") String trainCode,
            @ToolParam(description = "乘车日期, 格式: yyyy-MM-dd") String departDate,
            @ToolParam(description = "输出格式: text(表格) json(JSON)") String format) {

        if (!ticketParser.checkDate(departDate)) {
            return "日期不能早于今天";
        }

        try {
            List<RouteStationInfo> stations = ticketService.queryTrainRoute(trainCode, departDate);
            if (stations == null || stations.isEmpty()) {
                return "未查询到车次 " + trainCode + " 在 " + departDate + " 的经停站信息";
            }

            return switch (format.toLowerCase()) {
                case "json" -> ticketFormatter.formatJson(stations);
                default -> ticketFormatter.formatRouteStationsInfo(stations);
            };

        } catch (Exception e) {
            log.error("查询列车经停站失败", e);
            return "查询失败: " + e.getMessage();
        }
    }
}
