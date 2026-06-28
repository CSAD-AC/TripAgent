package uno.zhuchen.agent.tools.ticket12306.model;
import lombok.AllArgsConstructor; import lombok.Builder; import lombok.Data; import lombok.NoArgsConstructor;
import java.util.List;
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class InterlineInfo implements FilterableTicketInfo {
    private String lishi;
    private String startTime;
    private String startDate;
    private String middleDate;
    private String arriveDate;
    private String arriveTime;
    private String fromStationCode;
    private String fromStationName;
    private String middleStationCode;
    private String middleStationName;
    private String endStationCode;
    private String endStationName;
    private String startTrainCode;
    private String firstTrainNo;
    private String secondTrainNo;
    private int trainCount;
    private List<TicketInfo> ticketList;
    private boolean sameStation;
    private boolean sameTrain;
    private String waitTime;
    private List<String> dwFlag;
}
