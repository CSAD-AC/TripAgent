package uno.zhuchen.agent.tools.ticket12306.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.tools.ticket12306.model.TicketData;
import uno.zhuchen.agent.tools.ticket12306.model.TicketFilterOptions;
import uno.zhuchen.agent.tools.ticket12306.model.TicketInfo;
import uno.zhuchen.agent.tools.ticket12306.service.Ticket12306Service;
import uno.zhuchen.agent.tools.ticket12306.service.TicketFormatter;
import uno.zhuchen.agent.tools.ticket12306.service.TicketParser;

import java.util.List;
import java.util.Map;

@Component
public class TicketQueryTool {

    private static final Logger log = LoggerFactory.getLogger(TicketQueryTool.class);

    private final Ticket12306Service ticketService;
    private final TicketParser ticketParser;
    private final TicketFormatter ticketFormatter;

    public TicketQueryTool(Ticket12306Service ticketService,
                           TicketParser ticketParser,
                           TicketFormatter ticketFormatter) {
        this.ticketService = ticketService;
        this.ticketParser = ticketParser;
        this.ticketFormatter = ticketFormatter;
    }

    @Tool(description = "查询12306余票信息, 支持日期、出发/到达站、车次筛选、时间过滤、排序和格式化输出。"
            + "日期格式为 yyyy-MM-dd, 可通过 getCurrentDate 工具获取今天日期。"
            + "出发/到达站支持: 中文名称(如: 北京南/上海虹桥)或车站电报码(如: VNP/AOH)。"
            + "trainFilterFlags支持组合筛选: G=高铁/城际, D=动车, Z=直达特快, T=特快, K=快速, O=其他, F=复兴号, S=智能动车组。"
            + "sortFlag支持: startTime(出发时间), arriveTime(到达时间), duration(历时)。"
            + "format支持: text(表格文本), csv(CSV格式), json(JSON格式)。")
    public String getTickets(
            @ToolParam(description = "乘车日期, 格式: yyyy-MM-dd, 如: 2026-06-28") String date,
            @ToolParam(description = "出发站, 支持中文名称(如: 北京南)或电报码(如: VNP)") String fromStation,
            @ToolParam(description = "到达站, 支持中文名称(如: 上海虹桥)或电报码(如: AOH)") String toStation,
            @ToolParam(description = "车次筛选标志, 可组合: G=高铁/城际 D=动车 Z=直达特快 T=特快 K=快速 O=其他 F=复兴号 S=智能动车组, 如 GD 筛选高铁和动车") String trainFilterFlags,
            @ToolParam(description = "最早出发时间(小时, 0-24), 如 6 表示6点之后") int earliestStartTime,
            @ToolParam(description = "最晚出发时间(小时, 0-24), 如 22 表示22点之前") int latestStartTime,
            @ToolParam(description = "排序字段: startTime(出发时间) arriveTime(到达时间) duration(历时)") String sortFlag,
            @ToolParam(description = "是否反向排序") boolean sortReverse,
            @ToolParam(description = "返回结果数量限制, 0表示不限制") int limitedNum,
            @ToolParam(description = "输出格式: text(表格) csv(CSV) json(JSON)") String format) {

        // 验证日期
        if (!ticketParser.checkDate(date)) {
            return "日期不能早于今天, 请使用 getCurrentDate 工具确认当前日期";
        }

        // 解析车站代码
        String fromCode = ticketParser.parseStationCode(fromStation, ticketService.getStationDataService());
        String toCode = ticketParser.parseStationCode(toStation, ticketService.getStationDataService());
        if (fromCode == null || toCode == null) {
            return "无法识别车站: " + (fromCode == null ? fromStation : "") + (toCode == null ? " " + toStation : "");
        }

        try {
            // 查询余票
            Ticket12306Service.LeftTicketQueryResult queryResult = ticketService.queryTickets(date, fromStation, toStation);
            if (queryResult == null || queryResult.getRawData() == null || queryResult.getRawData().length == 0) {
                return "未查询到 " + date + " " + fromStation + " -> " + toStation + " 的列车信息";
            }

            // 解析原始管道分隔数据 -> TicketData -> TicketInfo
            List<TicketData> ticketsData = ticketParser.parseTicketsData(queryResult.getRawData());
            Map<String, String> stationMap = queryResult.getStationMap();
            List<TicketInfo> tickets = ticketParser.parseTicketsInfo(ticketsData, stationMap);

            // 筛选/排序/限制
            TicketFilterOptions options = TicketFilterOptions.builder()
                    .trainFilterFlags(trainFilterFlags)
                    .earliestStartTime(earliestStartTime)
                    .latestStartTime(latestStartTime)
                    .sortFlag(sortFlag)
                    .sortReverse(sortReverse)
                    .limitedNum(limitedNum)
                    .build();
            List<TicketInfo> filtered = ticketParser.filterTicketsInfo(tickets, options);

            if (filtered == null || filtered.isEmpty()) {
                return "筛选后无符合条件的车次";
            }

            // 格式化输出
            return switch (format.toLowerCase()) {
                case "csv" -> ticketFormatter.formatTicketsInfoCSV(filtered);
                case "json" -> ticketFormatter.formatJson(filtered);
                default -> ticketFormatter.formatTicketsInfo(filtered);
            };

        } catch (Exception e) {
            log.error("查询余票失败", e);
            return "查询失败: " + e.getMessage();
        }
    }
}
