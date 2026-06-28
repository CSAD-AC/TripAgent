package uno.zhuchen.agent.tools.ticket12306.model;
import lombok.AllArgsConstructor; import lombok.Builder; import lombok.Data; import lombok.NoArgsConstructor;
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RouteStationInfo {
    private String trainClassName;
    private String serviceType;
    private String endStationName;
    private String stationName;
    private String stationTrainCode;
    private String arriveTime;
    private String startTime;
    private String lishi;
    private String arriveDayStr;
}
