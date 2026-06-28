package uno.zhuchen.agent.tools.ticket12306.model;
import lombok.AllArgsConstructor; import lombok.Builder; import lombok.Data; import lombok.NoArgsConstructor;
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class TrainSearchData {
    private String date;
    private String fromStation;
    private String stationTrainCode;
    private String toStation;
    private String totalNum;
    private String trainNo;
}
