package uno.zhuchen.agent.tools.ticket12306.model;
import java.util.List;
public interface FilterableTicketInfo {
    String getStartTrainCode();
    String getStartTime();
    String getArriveTime();
    String getLishi();
    List<String> getDwFlag();
}
