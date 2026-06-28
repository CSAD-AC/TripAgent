package uno.zhuchen.agent.tools.ticket12306.model;
import lombok.AllArgsConstructor; import lombok.Builder; import lombok.Data; import lombok.NoArgsConstructor;
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RouteStationData {
    private String arriveDayStr;
    private String arriveTime;
    private String stationTrainCode;
    private String stationName;
    private String arriveDayDiff;
    private String startTime;
    private String wzNum;
    private String stationNo;
    private String runningTime;
    private String trainClassName;
    private String isStart;
    private String serviceType;
    private String endStationName;
}
