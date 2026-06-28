package uno.zhuchen.agent.tools.ticket12306.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import uno.zhuchen.agent.tools.ticket12306.model.InterlineInfo;
import uno.zhuchen.agent.tools.ticket12306.model.Price;
import uno.zhuchen.agent.tools.ticket12306.model.RouteStationInfo;
import uno.zhuchen.agent.tools.ticket12306.model.TicketInfo;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TicketFormatter {

    private final ObjectMapper mapper;

    public TicketFormatter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 格式化单张票证的座位状态
     */
    public String formatTicketStatus(String num) {
        if (num == null || num.isBlank() || num.equals("--") || num.equals("无")) return "无票";
        if (num.equals("有") || num.equals("充足")) return "有票";
        if (num.equals("候补") || num.contains("候补")) return "无票需候补";
        if (num.equals("0") || num.equals("*")) return "无票";
        try {
            int n = Integer.parseInt(num);
            return n > 0 ? "剩余" + n + "张票" : "无票";
        } catch (NumberFormatException e) {
            return num + "票";
        }
    }

    /**
     * 格式化单张票证信息为文本行
     */
    public String formatTicketLine(TicketInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append(info.getStartTrainCode()).append("|");
        sb.append(info.getFromStation()).append(" -> ").append(info.getToStation()).append("|");
        sb.append(info.getStartDate().substring(5)).append(" ").append(info.getStartTime());
        sb.append(" -> ");
        if (!info.getStartDate().equals(info.getArriveDate())) {
            sb.append(info.getArriveDate().substring(5)).append(" ");
        }
        sb.append(info.getArriveTime()).append("|");
        sb.append(info.getLishi());

        // 票价
        if (info.getPrices() != null && !info.getPrices().isEmpty()) {
            sb.append("|");
            for (Price p : info.getPrices()) {
                sb.append(p.getSeatName()).append(": ");
                if (p.getPrice() > 0) {
                    sb.append(String.format("%.0f", p.getPrice())).append("元");
                    if (p.getDiscount() != null && p.getDiscount() > 0 && p.getDiscount() < 100) {
                        sb.append("(").append(p.getDiscount()).append("折)");
                    }
                }
                sb.append("[").append(formatTicketStatus(p.getNum())).append("] ");
            }
        }

        // 特色标签
        if (info.getDwFlag() != null && !info.getDwFlag().isEmpty()) {
            sb.append("|").append(String.join(" ", info.getDwFlag()));
        }

        return sb.toString();
    }

    /**
     * 格式化余票查询结果 (text 格式)
     */
    public String formatTicketsInfo(List<TicketInfo> tickets) {
        if (tickets == null || tickets.isEmpty()) return "未查询到相关车次信息";
        StringBuilder sb = new StringBuilder();
        sb.append("车次|出发站 -> 到达站|出发时间 -> 到达时间|历时|票价|特色标签\n");
        sb.append("-".repeat(120)).append("\n");
        for (TicketInfo info : tickets) {
            sb.append(formatTicketLine(info)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 格式化余票查询结果 (CSV 格式)
     */
    public String formatTicketsInfoCSV(List<TicketInfo> tickets) {
        if (tickets == null || tickets.isEmpty()) return "未查询到相关车次信息";
        StringBuilder sb = new StringBuilder();
        sb.append("车次,出发站,到达站,出发时间,到达时间,历时,票价,特色标签\n");
        for (TicketInfo info : tickets) {
            sb.append(info.getStartTrainCode()).append(",");
            sb.append(info.getFromStation()).append(",");
            sb.append(info.getToStation()).append(",");
            sb.append(info.getStartTime()).append(",");
            sb.append(info.getArriveTime()).append(",");
            sb.append(info.getLishi()).append(",");
            if (info.getPrices() != null) {
                String pricesStr = info.getPrices().stream()
                        .map(p -> p.getSeatName() + ":" + (int)p.getPrice() + "元/" + formatTicketStatus(p.getNum()))
                        .collect(Collectors.joining("; "));
                sb.append("\"").append(pricesStr).append("\"");
            }
            sb.append(",");
            if (info.getDwFlag() != null) {
                sb.append("\"").append(String.join(" ", info.getDwFlag())).append("\"");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 格式化换乘信息 (text 格式)
     */
    public String formatInterlinesInfo(List<InterlineInfo> interlines) {
        if (interlines == null || interlines.isEmpty()) return "未查询到中转换乘车次信息";
        StringBuilder sb = new StringBuilder();
        sb.append("出发时间 -> 到达时间 | 出发 -> 中转 -> 到达 | 换乘标志 | 换乘等待时间 | 总历时\n");
        sb.append("-".repeat(120)).append("\n");
        for (InterlineInfo info : interlines) {
            sb.append(info.getStartTime()).append(" -> ").append(info.getArriveTime()).append(" | ");
            sb.append(info.getFromStationName()).append(" -> ");
            sb.append(info.getMiddleStationName()).append(" -> ");
            sb.append(info.getEndStationName()).append(" | ");
            // 换乘标志
            if (info.isSameTrain()) sb.append("同车换乘");
            else if (info.isSameStation()) sb.append("同站换乘");
            else sb.append("换站换乘");
            sb.append(" | ");
            sb.append(info.getWaitTime() != null ? info.getWaitTime() : "").append(" | ");
            sb.append(info.getLishi()).append("\n");

            // 子票证信息
            if (info.getTicketList() != null) {
                for (TicketInfo ti : info.getTicketList()) {
                    sb.append("  ").append(formatTicketLine(ti)).append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 格式化经停站信息 (text 格式)
     */
    public String formatRouteStationsInfo(List<RouteStationInfo> stations) {
        if (stations == null || stations.isEmpty()) return "未查询到经停站信息";
        StringBuilder sb = new StringBuilder();

        // 标题
        RouteStationInfo first = stations.get(0);
        if (first.getTrainClassName() != null || first.getServiceType() != null) {
            sb.append(first.getTrainClassName() != null ? first.getTrainClassName() : "");
            sb.append(" ");
            sb.append(first.getServiceType() != null ? first.getServiceType() : "");
            sb.append(" ");
            sb.append(first.getEndStationName() != null ? first.getEndStationName() : "");
            sb.append("\n");
        }

        sb.append("站序|车站|车次|到达时间|出发时间|历时(hh:mm)\n");
        sb.append("-".repeat(80)).append("\n");
        for (int i = 0; i < stations.size(); i++) {
            RouteStationInfo s = stations.get(i);
            sb.append(i + 1).append("|");
            sb.append(s.getStationName()).append("|");
            sb.append(s.getStationTrainCode()).append("|");
            sb.append(s.getArriveTime() != null ? s.getArriveTime() : "").append("|");
            sb.append(s.getStartTime() != null ? s.getStartTime() : "").append("|");
            sb.append(s.getLishi() != null ? s.getLishi() : "").append("\n");
        }
        return sb.toString();
    }

    /**
     * 格式化 JSON 输出
     */
    public String formatJson(Object obj) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "JSON 格式化失败: " + e.getMessage();
        }
    }
}
