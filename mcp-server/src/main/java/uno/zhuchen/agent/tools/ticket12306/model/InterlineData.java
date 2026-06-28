package uno.zhuchen.agent.tools.ticket12306.model;
import lombok.AllArgsConstructor; import lombok.Builder; import lombok.Data; import lombok.NoArgsConstructor;
import java.util.List;
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class InterlineData {
    private String allLishi;
    private int allLishiMinutes;
    private String arriveDate;
    private String arriveTime;
    private String endStationCode;
    private String endStationName;
    private String firstTrainNo;
    private String fromStationCode;
    private String fromStationName;
    private List<InterlineTicketData> fullList;
    private String isHeatTrain;
    private String isOutStation;
    private String lcWaitTime;
    private String lishiFlag;
    private String middleDate;
    private String middleStationCode;
    private String middleStationName;
    private String sameStation;
    private String sameTrain;
    private int score;
    private String scoreStr;
    private String scretstr;
    private String secondTrainNo;
    private String startTime;
    private int trainCount;
    private String trainDate;
    private String useTime;
    private String waitTime;
    private int waitTimeMinutes;
}
