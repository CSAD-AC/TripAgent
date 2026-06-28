package uno.zhuchen.agent.tools.ticket12306.config;

import java.util.Map;

public class Ticket12306Config {

    public static final String API_BASE = "https://kyfw.12306.cn";
    public static final String SEARCH_API_BASE = "https://search.12306.cn";
    public static final String WEB_URL = "https://www.12306.cn/index/";
    public static final String LCQUERY_INIT_URL = API_BASE + "/otn/lcQuery/init";

    public static final Map<String, String> SEAT_SHORT_TYPES = Map.ofEntries(
            Map.entry("swz", "商务座"), Map.entry("tz", "特等座"), Map.entry("zy", "一等座"),
            Map.entry("ze", "二等座"), Map.entry("gr", "高软卧"), Map.entry("srrb", "动卧"),
            Map.entry("rw", "软卧"), Map.entry("yw", "硬卧"), Map.entry("rz", "软座"),
            Map.entry("yz", "硬座"), Map.entry("wz", "无座"), Map.entry("qt", "其他"),
            Map.entry("gg", ""), Map.entry("yb", "")
    );

    public static final Map<String, SeatType> SEAT_TYPES = Map.ofEntries(
            Map.entry("9", new SeatType("商务座", "swz")), Map.entry("P", new SeatType("特等座", "tz")),
            Map.entry("M", new SeatType("一等座", "zy")), Map.entry("D", new SeatType("优选一等座", "zy")),
            Map.entry("O", new SeatType("二等座", "ze")), Map.entry("S", new SeatType("二等包座", "ze")),
            Map.entry("6", new SeatType("高级软卧", "gr")), Map.entry("A", new SeatType("高级动卧", "gr")),
            Map.entry("4", new SeatType("软卧", "rw")), Map.entry("I", new SeatType("一等卧", "rw")),
            Map.entry("F", new SeatType("动卧", "rw")), Map.entry("3", new SeatType("硬卧", "yw")),
            Map.entry("J", new SeatType("二等卧", "yw")), Map.entry("2", new SeatType("软座", "rz")),
            Map.entry("1", new SeatType("硬座", "yz")), Map.entry("W", new SeatType("无座", "wz")),
            Map.entry("WZ", new SeatType("无座", "wz")), Map.entry("H", new SeatType("其他", "qt"))
    );

    public static final String[] DW_FLAGS = { "智能动车组", "复兴号", "静音车厢", "温馨动卧", "动感号", "支持选铺", "老年优惠" };
    public static final int PRICE_STR_LENGTH = 10;
    public static final int DISCOUNT_STR_LENGTH = 5;

    public record SeatType(String name, String shortName) { }
}
