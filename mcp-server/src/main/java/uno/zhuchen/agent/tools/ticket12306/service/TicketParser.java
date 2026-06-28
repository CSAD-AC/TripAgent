package uno.zhuchen.agent.tools.ticket12306.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uno.zhuchen.agent.tools.ticket12306.config.Ticket12306Config;
import uno.zhuchen.agent.tools.ticket12306.model.*;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TicketParser {

    private static final Logger log = LoggerFactory.getLogger(TicketParser.class);
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");

    // 列车筛选器: 字母 -> (code, flag) -> boolean
    private static final Map<Character, BiFunction<String, List<String>, Boolean>> TRAIN_FILTERS = Map.of(
            'G', (code, flag) -> code.startsWith("G") || code.startsWith("C"),
            'D', (code, flag) -> code.startsWith("D"),
            'Z', (code, flag) -> code.startsWith("Z"),
            'T', (code, flag) -> code.startsWith("T"),
            'K', (code, flag) -> code.startsWith("K"),
            'O', (code, flag) -> !code.startsWith("G") && !code.startsWith("C") && !code.startsWith("D")
                    && !code.startsWith("Z") && !code.startsWith("T") && !code.startsWith("K"),
            'F', (code, flag) -> flag != null && flag.contains("复兴号"),
            'S', (code, flag) -> flag != null && flag.contains("智能动车组")
    );

    // 票价字段名映射: shortName -> TicketData field getter
    private static final Map<String, Function<TicketData, String>> SEAT_NUM_GETTERS = new LinkedHashMap<>();
    static {
        SEAT_NUM_GETTERS.put("swz", TicketData::getSwzNum);
        SEAT_NUM_GETTERS.put("tz", TicketData::getTzNum);
        SEAT_NUM_GETTERS.put("zy", TicketData::getZyNum);
        SEAT_NUM_GETTERS.put("ze", TicketData::getZeNum);
        SEAT_NUM_GETTERS.put("gr", TicketData::getGrNum);
        SEAT_NUM_GETTERS.put("srrb", TicketData::getSrrbNum);
        SEAT_NUM_GETTERS.put("yw", TicketData::getYwNum);
        SEAT_NUM_GETTERS.put("rz", TicketData::getRzNum);
        SEAT_NUM_GETTERS.put("yz", TicketData::getYzNum);
        SEAT_NUM_GETTERS.put("wz", TicketData::getWzNum);
        SEAT_NUM_GETTERS.put("qt", TicketData::getQtNum);
        SEAT_NUM_GETTERS.put("rw", TicketData::getRwNum);
        SEAT_NUM_GETTERS.put("yb", TicketData::getYbNum);
        SEAT_NUM_GETTERS.put("gg", TicketData::getGgNum);
    }

    /**
     * 将管道分隔的原始字符串数组解析为 TicketData 列表
     */
    public List<TicketData> parseTicketsData(String[] rawData) {
        List<TicketData> list = new ArrayList<>();
        Field[] fields = TicketData.class.getDeclaredFields();
        for (String row : rawData) {
            if (row == null || row.isBlank()) continue;
            String[] parts = row.split("\\|", -1);
            if (parts.length < TicketDataKeys.TOTAL_FIELDS) continue;
            try {
                TicketData.TicketDataBuilder builder = TicketData.builder();
                for (Field f : fields) {
                    f.setAccessible(true);
                    try {
                        int idx = TicketDataKeys.class.getField(f.getName().toUpperCase()).getInt(null);
                        String val = idx < parts.length ? parts[idx] : "";
                        if (f.getType() == String.class) {
                            f.set(builder, val);
                        }
                    } catch (Exception ignored) {}
                }
                list.add(builder.build());
            } catch (Exception e) {
                log.warn("解析 TicketData 失败: {}", e.getMessage());
            }
        }
        return list;
    }

    /**
     * 将 TicketData 列表解析为 TicketInfo (含票价、特色标签)
     */
    public List<TicketInfo> parseTicketsInfo(List<TicketData> ticketsData, StationDataService stationService) {
        return ticketsData.stream().map(td -> {
            List<Price> prices = extractPrices(td.getYpInfo(), td.getSeatDiscountInfo(), td);
            List<String> flags = extractDWFlags(td.getDwFlag());
            String startDate = td.getStartTrainDate();
            String arriveDate = calcArriveDate(startDate, td.getLishi(), td.getStartTime(), td.getArriveTime());

            return TicketInfo.builder()
                    .trainNo(td.getTrainNo())
                    .startTrainCode(td.getStationTrainCode())
                    .startDate(startDate)
                    .startTime(td.getStartTime())
                    .arriveDate(arriveDate)
                    .arriveTime(td.getArriveTime())
                    .lishi(td.getLishi())
                    .fromStation(stationService.getStationByTelecode(td.getFromStationTelecode()) != null
                            ? stationService.getStationByTelecode(td.getFromStationTelecode()).getStationName()
                            : td.getFromStationTelecode())
                    .toStation(stationService.getStationByTelecode(td.getToStationTelecode()) != null
                            ? stationService.getStationByTelecode(td.getToStationTelecode()).getStationName()
                            : td.getToStationTelecode())
                    .fromStationTelecode(td.getFromStationTelecode())
                    .toStationTelecode(td.getToStationTelecode())
                    .prices(prices)
                    .dwFlag(flags)
                    .build();
        }).collect(Collectors.toList());
    }

    /**
     * 从 yp_info 编码中提取票价
     * yp_info: 10 字符一块, [0]=座型, [1..5]=价格(分/10), [6..9]=预留
     * seat_discount_info: 5 字符一块, [0]=座型, [1..4]=折扣
     */
    public List<Price> extractPrices(String ypInfo, String seatDiscountInfo, TicketData ticketData) {
        List<Price> prices = new ArrayList<>();
        if (ypInfo == null || ypInfo.isBlank()) return prices;

        Map<String, Integer> discountMap = new HashMap<>();
        if (seatDiscountInfo != null && !seatDiscountInfo.isBlank()) {
            String sd = seatDiscountInfo;
            for (int i = 0; i + Ticket12306Config.DISCOUNT_STR_LENGTH <= sd.length(); i += Ticket12306Config.DISCOUNT_STR_LENGTH) {
                String block = sd.substring(i, i + Ticket12306Config.DISCOUNT_STR_LENGTH);
                try {
                    discountMap.put(String.valueOf(block.charAt(0)),
                            Integer.parseInt(block.substring(1, 5).trim()));
                } catch (Exception ignored) {}
            }
        }

        for (int i = 0; i + Ticket12306Config.PRICE_STR_LENGTH <= ypInfo.length(); i += Ticket12306Config.PRICE_STR_LENGTH) {
            String block = ypInfo.substring(i, i + Ticket12306Config.PRICE_STR_LENGTH);
            String seatCode = String.valueOf(block.charAt(0));
            if (seatCode.isBlank() || seatCode.equals(" ")) continue;

            // 如果后 4 位 >= 3000 则强制无座
            int trailer = 0;
            try { trailer = Integer.parseInt(block.substring(6, 10).trim()); } catch (Exception ignored) {}
            if (trailer >= 3000) seatCode = "W";

            String finalSeatCode = seatCode;
            Ticket12306Config.SeatType seatType = Ticket12306Config.SEAT_TYPES.entrySet().stream()
                    .filter(e -> e.getKey().equals(finalSeatCode)).map(Map.Entry::getValue).findFirst().orElse(null);
            if (seatType == null) {
                seatType = Ticket12306Config.SEAT_TYPES.get("H");
                if (seatType == null) continue;
            }

            // 价格: 分/10 -> 元
            double price = 0;
            try { price = Double.parseDouble(block.substring(1, 6).trim()) / 10.0; } catch (Exception ignored) {}

            // 座位数量
            Function<TicketData, String> getter = SEAT_NUM_GETTERS.get(seatType.shortName());
            String num = "";
            if (getter != null) num = getter.apply(ticketData);

            // 折扣
            Integer discount = discountMap.get(seatCode);

            prices.add(Price.builder()
                    .seatName(seatType.name())
                    .shortName(seatType.shortName())
                    .seatTypeCode(seatCode)
                    .num(num)
                    .price(price)
                    .discount(discount)
                    .build());
        }

        // 去重 (座型相同)
        Map<String, Price> dedup = new LinkedHashMap<>();
        for (Price p : prices) dedup.put(p.getShortName(), p);
        return new ArrayList<>(dedup.values());
    }

    /**
     * 从 dw_flag 编码中提取特色标签
     * # 分隔, 每位标识不同特性
     */
    public List<String> extractDWFlags(String dwFlag) {
        List<String> flags = new ArrayList<>();
        if (dwFlag == null || dwFlag.isBlank()) return flags;

        String[] parts = dwFlag.split("#", -1);
        String[] dw = Ticket12306Config.DW_FLAGS;

        // 位 0: 智能动车组
        if (parts.length > 0 && "5".equals(parts[0]) && dw.length > 0)
            flags.add(dw[0]);

        // 位 1: 复兴号
        if (parts.length > 1 && "1".equals(parts[1]) && dw.length > 1)
            flags.add(dw[1]);

        // 位 2: 静音车厢 / 温馨动卧
        if (parts.length > 2 && parts[2] != null && parts[2].length() > 0) {
            char c = parts[2].charAt(0);
            if (c == 'Q' && dw.length > 2) flags.add(dw[2]);
            else if (c == 'R' && dw.length > 3) flags.add(dw[3]);
        }

        // 位 5: 动感号
        if (parts.length > 5 && "D".equals(parts[5]) && dw.length > 4)
            flags.add(dw[4]);

        // 位 6: 支持选铺
        if (parts.length > 6 && !"z".equals(parts[6]) && !parts[6].isBlank() && dw.length > 5)
            flags.add(dw[5]);

        // 位 7: 老年优惠
        if (parts.length > 7 && !"z".equals(parts[7]) && !parts[7].isBlank() && dw.length > 6)
            flags.add(dw[6]);

        return flags;
    }

    /**
     * 解析 RouteStationData 列表为 RouteStationInfo
     */
    public List<RouteStationInfo> parseRouteStationsInfo(List<RouteStationData> dataList) {
        List<RouteStationInfo> result = new ArrayList<>();
        for (int i = 0; i < dataList.size(); i++) {
            RouteStationData d = dataList.get(i);
            String lishi = (i == 0 || "Y".equals(d.getIsStart())) ? "" : calcLishi(d.getStartTime(), d.getRunningTime());
            result.add(RouteStationInfo.builder()
                    .trainClassName(d.getTrainClassName())
                    .serviceType(d.getServiceType())
                    .endStationName(d.getEndStationName())
                    .stationName(d.getStationName())
                    .stationTrainCode(d.getStationTrainCode())
                    .arriveTime(d.getArriveTime())
                    .startTime(d.getStartTime())
                    .lishi(lishi)
                    .arriveDayStr(d.getArriveDayStr())
                    .build());
        }
        return result;
    }

    /**
     * 解析中转换乘数据
     */
    public List<InterlineInfo> parseInterlinesInfo(List<InterlineData> interlineDataList, StationDataService stationService) {
        return interlineDataList.stream().map(data -> {
            List<TicketInfo> ticketList = new ArrayList<>();
            if (data.getFullList() != null) {
                for (InterlineTicketData itd : data.getFullList()) {
                    TicketData td = interlineTicketToTicketData(itd);
                    List<Price> prices = extractPrices(td.getYpInfo(), td.getSeatDiscountInfo(), td);
                    List<String> flags = extractDWFlags(itd.getDwFlag());
                    String arriveDate = calcArriveDate(itd.getStartTrainDate(), itd.getLishi(), itd.getStartTime(), itd.getArriveTime());
                    ticketList.add(TicketInfo.builder()
                            .trainNo(itd.getTrainNo())
                            .startTrainCode(itd.getStationTrainCode())
                            .startDate(itd.getStartTrainDate())
                            .startTime(itd.getStartTime())
                            .arriveDate(arriveDate)
                            .arriveTime(itd.getArriveTime())
                            .lishi(itd.getLishi())
                            .fromStation(itd.getFromStationName())
                            .toStation(itd.getToStationName())
                            .fromStationTelecode(itd.getFromStationTelecode())
                            .toStationTelecode(itd.getToStationTelecode())
                            .prices(prices)
                            .dwFlag(flags)
                            .build());
                }
            }

            String lishi = extractLishi(data.getUseTime());

            return InterlineInfo.builder()
                    .lishi(lishi)
                    .startTime(data.getStartTime())
                    .startDate(data.getTrainDate())
                    .middleDate(data.getMiddleDate())
                    .arriveDate(data.getArriveDate())
                    .arriveTime(data.getArriveTime())
                    .fromStationCode(data.getFromStationCode())
                    .fromStationName(data.getFromStationName())
                    .middleStationCode(data.getMiddleStationCode())
                    .middleStationName(data.getMiddleStationName())
                    .endStationCode(data.getEndStationCode())
                    .endStationName(data.getEndStationName())
                    .startTrainCode(data.getFirstTrainNo() + "->" + data.getSecondTrainNo())
                    .firstTrainNo(data.getFirstTrainNo())
                    .secondTrainNo(data.getSecondTrainNo())
                    .trainCount(data.getTrainCount())
                    .ticketList(ticketList)
                    .sameStation("Y".equals(data.getSameStation()))
                    .sameTrain("Y".equals(data.getSameTrain()))
                    .waitTime(data.getWaitTime())
                    .dwFlag(ticketList.stream().flatMap(t -> t.getDwFlag().stream()).distinct().collect(Collectors.toList()))
                    .build();
        }).collect(Collectors.toList());
    }

    /**
     * 将 InterlineTicketData 映射为 TicketData (便于复用票价解析)
     */
    private TicketData interlineTicketToTicketData(InterlineTicketData itd) {
        return TicketData.builder()
                .trainNo(itd.getTrainNo())
                .stationTrainCode(itd.getStationTrainCode())
                .startStationTelecode(itd.getStartStationTelecode())
                .endStationTelecode(itd.getEndStationTelecode())
                .fromStationTelecode(itd.getFromStationTelecode())
                .toStationTelecode(itd.getToStationTelecode())
                .startTime(itd.getStartTime())
                .arriveTime(itd.getArriveTime())
                .lishi(itd.getLishi())
                .startTrainDate(itd.getStartTrainDate())
                .ypInfo(itd.getYpInfo())
                .dwFlag(itd.getDwFlag())
                .seatDiscountInfo(itd.getSeatDiscountInfo())
                .swzNum(itd.getSwzNum()).zyNum(itd.getZyNum()).zeNum(itd.getZeNum())
                .grNum(itd.getGrNum()).srrbNum(itd.getSrrbNum()).rwNum(itd.getRwNum())
                .ywNum(itd.getYwNum()).rzNum(itd.getRzNum()).yzNum(itd.getYzNum())
                .wzNum(itd.getWzNum()).qtNum(itd.getQtNum()).tzNum(itd.getTzNum())
                .ggNum("").ybNum(itd.getYbNum()).build();
    }

    /**
     * 计算到达日期
     */
    public String calcArriveDate(String startDate, String lishi, String startTime, String arriveTime) {
        if (startDate == null || startDate.length() != 10 || arriveTime == null || arriveTime.length() < 5) return startDate;
        try {
            if (arriveTime.compareTo(startTime) > 0) return startDate;
            // 跨日: 检查 lishi 是否包含 "天"
            if (lishi != null && lishi.contains("天")) {
                int days = Integer.parseInt(lishi.replaceAll("\\D+", ""));
                return LocalDate.parse(startDate, DateTimeFormatter.ISO_LOCAL_DATE).plusDays(days)
                        .format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            return LocalDate.parse(startDate, DateTimeFormatter.ISO_LOCAL_DATE).plusDays(1)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return startDate;
        }
    }

    /**
     * 提取历时文本 (X小时Y分钟 -> hh:mm)
     */
    public String extractLishi(String useTime) {
        if (useTime == null || useTime.isBlank()) return "";
        int h = 0, m = 0;
        StringBuilder sb = new StringBuilder();
        for (char c : useTime.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c);
            else {
                if (c == '小' || c == 'h') { h = Integer.parseInt(sb.toString()); sb.setLength(0); }
                else if (c == '分' || c == 'm') { m = Integer.parseInt(sb.toString()); sb.setLength(0); }
            }
        }
        if (sb.length() > 0) m = Integer.parseInt(sb.toString());
        h += m / 60; m %= 60;
        return String.format("%02d:%02d", h, m);
    }

    /**
     * 计算历时: startTime + runningTime
     */
    private String calcLishi(String startTime, String runningTime) {
        if (startTime == null || startTime.length() < 5 || runningTime == null || runningTime.length() < 5) return "";
        try {
            int sh = Integer.parseInt(startTime.substring(0, 2));
            int sm = Integer.parseInt(startTime.substring(3, 5));
            int rh = Integer.parseInt(runningTime.substring(0, 2));
            int rm = Integer.parseInt(runningTime.substring(3, 5));
            int total = sh * 60 + sm + rh * 60 + rm;
            int h = total / 60 % 24;
            int m = total % 60;
            return String.format("%02d:%02d", h, m);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 检查日期不早于今天 (上海时区)
     */
    public boolean checkDate(String dateStr) {
        try {
            LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate today = LocalDate.now(CN_ZONE);
            return !date.isBefore(today);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 通用票证筛选/排序/限制 (适用于 TicketInfo 和 InterlineInfo)
     */
    @SuppressWarnings("unchecked")
    public <T extends FilterableTicketInfo> List<T> filterTicketsInfo(List<T> tickets, TicketFilterOptions options) {
        if (tickets == null || tickets.isEmpty()) return tickets;
        List<T> filtered = new ArrayList<>(tickets);

        // 列车筛选
        String flags = options.getTrainFilterFlags();
        if (flags != null && !flags.isEmpty()) {
            filtered = filtered.stream().filter(t -> {
                String code = t.getStartTrainCode();
                List<String> dw = t.getDwFlag();
                for (char f : flags.toCharArray()) {
                    BiFunction<String, List<String>, Boolean> filter = TRAIN_FILTERS.get(f);
                    if (filter != null && filter.apply(code, dw)) return true;
                }
                return false;
            }).collect(Collectors.toList());
        }

        // 时间筛选
        int earliest = options.getEarliestStartTime();
        int latest = options.getLatestStartTime();
        if (earliest > 0 || latest < 24) {
            filtered = filtered.stream().filter(t -> {
                String st = t.getStartTime();
                if (st == null || st.length() < 2) return true;
                try {
                    int h = Integer.parseInt(st.substring(0, 2));
                    return h >= earliest && h < latest;
                } catch (Exception e) { return true; }
            }).collect(Collectors.toList());
        }

        // 排序
        String sortFlag = options.getSortFlag();
        if (sortFlag != null && !sortFlag.isEmpty()) {
            Comparator<FilterableTicketInfo> comp = getComparator(sortFlag, options.isSortReverse());
            filtered.sort((a, b) -> comp.compare(a, b));
        }

        // 限制
        int limited = options.getLimitedNum();
        if (limited > 0 && filtered.size() > limited) {
            filtered = new ArrayList<>(filtered.subList(0, limited));
        }

        return filtered;
    }

    /**
     * 获取比较器
     */
    public Comparator<FilterableTicketInfo> getComparator(String sortFlag, boolean sortReverse) {
        Comparator<FilterableTicketInfo> comp;
        switch (sortFlag) {
            case "startTime":
                comp = Comparator.comparing(t -> parseMinutes(t.getStartTime()));
                break;
            case "arriveTime":
                comp = (a, b) -> {
                    int cmp = compareDateTime(a.getArriveTime(), b.getArriveTime());
                    if (cmp != 0) return cmp;
                    return compareDateTime(a.getStartTime(), b.getStartTime());
                };
                break;
            case "duration":
                comp = Comparator.comparingInt(t -> parseDurationMinutes(t.getLishi()));
                break;
            default:
                comp = (a, b) -> 0;
        }
        return sortReverse ? comp.reversed() : comp;
    }

    private int compareDateTime(String timeA, String timeB) {
        return Integer.compare(parseMinutes(timeA), parseMinutes(timeB));
    }

    private int parseMinutes(String hhmm) {
        if (hhmm == null || hhmm.length() < 5) return 0;
        try {
            return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
        } catch (Exception e) { return 0; }
    }

    private int parseDurationMinutes(String lishi) {
        if (lishi == null || lishi.isBlank()) return 0;
        try {
            String[] parts = lishi.split(":");
            if (parts.length == 2) {
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            }
        } catch (Exception ignored) {}
        // 尝试解析 "X小时Y分钟" 格式
        int h = 0, m = 0;
        StringBuilder buf = new StringBuilder();
        for (char c : lishi.toCharArray()) {
            if (Character.isDigit(c)) buf.append(c);
            else {
                if (buf.length() > 0) {
                    if (c == '小' || c == '时' || c == 'h') { h = Integer.parseInt(buf.toString()); buf.setLength(0); }
                }
            }
        }
        if (buf.length() > 0) m = Integer.parseInt(buf.toString());
        return h * 60 + m;
    }

    /**
     * 获取当前日期 (上海时区, yyyy-MM-dd)
     */
    public String getCurrentDateStr() {
        return LocalDate.now(CN_ZONE).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * 从中文名/代码解析车站代码
     */
    public String parseStationCode(String station, StationDataService stationService) {
        if (station == null || station.isBlank()) return null;
        if (station.endsWith("站")) station = station.substring(0, station.length() - 1);
        // 如果是 3 位大写字母且是 telecode
        if (station.matches("^[A-Z]{3}$")) {
            StationData sd = stationService.getStationByTelecode(station);
            if (sd != null) return station;
        }
        StationInfo si = stationService.getStationByName(station);
        return si != null ? si.getStationCode() : null;
    }
}
