package uno.zhuchen.agent.tools.ticket12306.model;
import lombok.AllArgsConstructor; import lombok.Builder; import lombok.Data; import lombok.NoArgsConstructor;
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Price {
    private String seatName; private String shortName; private String seatTypeCode;
    private String num; private double price; private Integer discount;
}
