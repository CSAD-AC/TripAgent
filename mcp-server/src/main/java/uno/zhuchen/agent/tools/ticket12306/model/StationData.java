package uno.zhuchen.agent.tools.ticket12306.model;
import lombok.AllArgsConstructor; import lombok.Builder; import lombok.Data; import lombok.NoArgsConstructor;
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class StationData {
    private String stationId; private String stationName; private String stationCode;
    private String stationPinyin; private String stationShort; private String stationIndex;
    private String code; private String city; private String r1; private String r2;
}
