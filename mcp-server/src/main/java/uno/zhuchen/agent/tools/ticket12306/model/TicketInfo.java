package uno.zhuchen.agent.tools.ticket12306.model;
import lombok.AllArgsConstructor; import lombok.Builder; import lombok.Data; import lombok.NoArgsConstructor;
import java.util.List;
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class TicketInfo implements FilterableTicketInfo {
    private String trainNo;
    private String startTrainCode;
    private String startDate;
    private String startTime;
    private String arriveDate;
    private String arriveTime;
    private String lishi;
    private String fromStation;
    private String toStation;
    private String fromStationTelecode;
    private String toStationTelecode;
    private List<Price> prices;
    private List<String> dwFlag;
}
