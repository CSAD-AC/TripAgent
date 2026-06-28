package uno.zhuchen.agent.tools.ticket12306.model;
import lombok.AllArgsConstructor; import lombok.Builder; import lombok.Data; import lombok.NoArgsConstructor;
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class TicketFilterOptions {
    private String trainFilterFlags;
    private int earliestStartTime;
    private int latestStartTime;
    private String sortFlag;
    private boolean sortReverse;
    private int limitedNum;
}
