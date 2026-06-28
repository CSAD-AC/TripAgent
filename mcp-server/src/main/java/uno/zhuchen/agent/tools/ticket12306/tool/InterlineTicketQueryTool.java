package uno.zhuchen.agent.tools.ticket12306.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.tools.ticket12306.model.InterlineData;
import uno.zhuchen.agent.tools.ticket12306.model.InterlineInfo;
import uno.zhuchen.agent.tools.ticket12306.model.TicketFilterOptions;
import uno.zhuchen.agent.tools.ticket12306.service.Ticket12306Service;
import uno.zhuchen.agent.tools.ticket12306.service.Ticket12306Service.InterlineQueryResult;
import uno.zhuchen.agent.tools.ticket12306.service.TicketFormatter;
import uno.zhuchen.agent.tools.ticket12306.service.TicketParser;

import java.util.ArrayList;
import java.util.List;

@Component
public class InterlineTicketQueryTool {

    private static final Logger log = LoggerFactory.getLogger(InterlineTicketQueryTool.class);

    private final Ticket12306Service ticketService;
    private final TicketParser ticketParser;
    private final TicketFormatter ticketFormatter;

    public InterlineTicketQueryTool(Ticket12306Service ticketService,
                                    TicketParser ticketParser,
                                    TicketFormatter ticketFormatter) {
        this.ticketService = ticketService;
        this.ticketParser = ticketParser;
        this.ticketFormatter = ticketFormatter;
    }

    @Tool(description = "查询12306中转换乘方案, 支持中途站、筛选、排序、格式化。"
            + "日期格式 yyyy-MM-dd, 车站支持中文名称或电报码。"
            + "trainFilterFlags支持组合筛选: G=高铁/城际, D=动车, Z=直达特快, T=特快, K=快速, O=其他, F=复兴号, S=智能动车组。"
            + "sortFlag支持: startTime(出发时间), arriveTime(到达时间), duration(历时)。"
            + "format支持: text(表格文本), json(JSON格式)。")
    public String getInterlineTickets(
            @ToolParam(description = "乘车日期, 格式: yyyy-MM-dd") String date,
            @ToolParam(description = "出发站, 支持中文名称或电报码") String fromStation,
            @ToolParam(description = "到达站, 支持中文名称或电报码") String toStation,
            @ToolParam(description = "中转站, 如果为空则由12306自动推荐") String middleStation,
            @ToolParam(description = "是否显示无座选项") boolean showWZ,
            @ToolParam(description = "车次筛选标志, 可组合: G D Z T K O F S") String trainFilterFlags,
            @ToolParam(description = "最早出发时间(小时)") int earliestStartTime,
            @ToolParam(description = "最晚出发时间(小时)") int latestStartTime,
            @ToolParam(description = "排序字段: startTime arriveTime duration") String sortFlag,
            @ToolParam(description = "是否反向排序") boolean sortReverse,
            @ToolParam(description = "返回结果数量限制, 默认10条") int limitedNum,
            @ToolParam(description = "输出格式: text(表格) json(JSON)") String format) {

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

        // 分页查询
        List<InterlineData> allData = new ArrayList<>();
        String canQuery = "Y";
        int resultIndex = 0;
        int maxResults = Math.max(limitedNum, 10); // 至少查10条

        try {
            while ("Y".equals(canQuery) && allData.size() < maxResults) {
                InterlineQueryResult pageResult = ticketService.queryInterlineTickets(
                        date, fromStation, toStation,
                        middleStation != null && !middleStation.isBlank() ? middleStation : null,
                        showWZ, resultIndex, canQuery);

                if (pageResult == null) break;

                canQuery = pageResult.getCanQuery();
                if (pageResult.getInterlineData() != null) {
                    allData.addAll(pageResult.getInterlineData());
                }
                resultIndex = allData.size();
            }

            if (allData.isEmpty()) {
                return "未查询到 " + date + " " + fromStation + " -> " + toStation + " 的中转换乘方案";
            }

            // 解析
            List<InterlineInfo> interlinesInfo = ticketParser.parseInterlinesInfo(allData, ticketService.getStationDataService());

            // 筛选/排序/限制
            TicketFilterOptions options = TicketFilterOptions.builder()
                    .trainFilterFlags(trainFilterFlags)
                    .earliestStartTime(earliestStartTime)
                    .latestStartTime(latestStartTime)
                    .sortFlag(sortFlag)
                    .sortReverse(sortReverse)
                    .limitedNum(limitedNum)
                    .build();
            List<InterlineInfo> filtered = ticketParser.filterTicketsInfo(interlinesInfo, options);

            if (filtered == null || filtered.isEmpty()) {
                return "筛选后无符合条件的换乘方案";
            }

            // 格式化输出
            return switch (format.toLowerCase()) {
                case "json" -> ticketFormatter.formatJson(filtered);
                default -> ticketFormatter.formatInterlinesInfo(filtered);
            };

        } catch (Exception e) {
            log.error("查询中转换乘失败", e);
            return "查询失败: " + e.getMessage();
        }
    }
}
