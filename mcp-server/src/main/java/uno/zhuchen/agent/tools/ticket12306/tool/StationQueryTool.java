package uno.zhuchen.agent.tools.ticket12306.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.tools.ticket12306.model.StationData;
import uno.zhuchen.agent.tools.ticket12306.model.StationInfo;
import uno.zhuchen.agent.tools.ticket12306.service.StationDataService;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class StationQueryTool {

    private final StationDataService stationDataService;

    public StationQueryTool(StationDataService stationDataService) {
        this.stationDataService = stationDataService;
    }

    @Tool(description = "根据中文城市名称查询该城市下所有车站代码及名称, 如: 北京")
    public String getStationsCodeInCity(
            @ToolParam(description = "中文城市名称, 如: 北京") String city) {
        List<StationInfo> stations = stationDataService.getStationsInCity(city);
        if (stations == null || stations.isEmpty()) {
            return "未找到城市 " + city + " 的车站信息";
        }
        return stations.stream()
                .map(s -> s.getStationCode() + " - " + s.getStationName())
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "根据中文城市名称列表(竖线|分隔)查询各城市对应的主要车站代码, 如: 北京|上海|广州")
    public String getStationCodeOfCitys(
            @ToolParam(description = "中文城市名称, 多个城市用 | 分隔, 如: 北京|上海|广州") String citys) {
        StringBuilder sb = new StringBuilder();
        String[] cities = citys.split("\\|");
        for (String city : cities) {
            city = city.trim();
            if (city.isEmpty()) continue;
            StationInfo info = stationDataService.getCityCode(city);
            if (info != null) {
                sb.append(city).append(" - ").append(info.getStationCode()).append("\n");
            } else {
                // 如果没有精确匹配城市名, 取该城市第一个车站
                List<StationInfo> stations = stationDataService.getStationsInCity(city);
                if (stations != null && !stations.isEmpty()) {
                    sb.append(city).append(" - ").append(stations.get(0).getStationCode()).append("\n");
                } else {
                    sb.append(city).append(" - 未找到\n");
                }
            }
        }
        return sb.toString().trim();
    }

    @Tool(description = "根据中文车站名称(竖线|分隔)查询车站代码, 如: 北京南|上海虹桥|广州南")
    public String getStationCodeByNames(
            @ToolParam(description = "中文车站名称, 多个车站用 | 分隔, 如: 北京南|上海虹桥|广州南") String stationNames) {
        StringBuilder sb = new StringBuilder();
        String[] names = stationNames.split("\\|");
        for (String name : names) {
            name = name.trim();
            if (name.isEmpty()) continue;
            if (name.endsWith("站")) name = name.substring(0, name.length() - 1);
            StationInfo info = stationDataService.getStationByName(name);
            if (info != null) {
                sb.append(name).append(" - ").append(info.getStationCode()).append("\n");
            } else {
                sb.append(name).append(" - 未找到\n");
            }
        }
        return sb.toString().trim();
    }

    @Tool(description = "根据车站电报码(3位大写字母)查询车站详细信息, 如: VNP(北京南)")
    public String getStationByTelecode(
            @ToolParam(description = "车站电报码(3位大写字母), 如: VNP") String stationTelecode) {
        StationData data = stationDataService.getStationByTelecode(stationTelecode);
        if (data == null) {
            return "未找到电报码 " + stationTelecode + " 对应的车站";
        }
        return String.format("车站名称: %s\n电报码: %s\n拼音: %s\n简拼: %s\n城市: %s\n序号: %s\nID: %s\n代码: %s",
                data.getStationName(), data.getStationCode(), data.getStationPinyin(),
                data.getStationShort(), data.getCity(), data.getStationIndex(),
                data.getStationId(), data.getCode());
    }
}
