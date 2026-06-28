package uno.zhuchen.agent.tools.ticket12306.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import uno.zhuchen.agent.tools.ticket12306.config.Ticket12306Config;
import uno.zhuchen.agent.tools.ticket12306.model.*;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class Ticket12306Service {

    private static final Logger log = LoggerFactory.getLogger(Ticket12306Service.class);
    private static final Pattern LCQUERY_PATH_PATTERN = Pattern.compile("var lc_search_url\\s*=\\s*'([^']+)'");
    private static final int REQUEST_TIMEOUT = 15;

    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final StationDataService stationService;
    private final TicketParser ticketParser;

    public Ticket12306Service(WebClient.Builder webClientBuilder,
                              ObjectMapper mapper,
                              StationDataService stationService,
                              TicketParser ticketParser) {
        this.webClient = webClientBuilder
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
        this.mapper = mapper;
        this.stationService = stationService;
        this.ticketParser = ticketParser;
    }

    // ==================== Cookie 管理 ====================

    public StationDataService getStationDataService() { return stationService; }

    /**
     * 获取 12306 Cookie
     */
    public Map<String, String> getCookie() {
        try {
            return webClient.get()
                    .uri(Ticket12306Config.API_BASE + "/otn/leftTicket/init")
                    .exchangeToMono(resp -> {
                        Map<String, String> cookies = new LinkedHashMap<>();
                        List<String> setCookieHeaders = resp.headers().asHttpHeaders().get("Set-Cookie");
                        if (setCookieHeaders != null) {
                            for (String header : setCookieHeaders) {
                                String[] parts = header.split(";");
                                if (parts.length > 0) {
                                    String[] kv = parts[0].split("=", 2);
                                    if (kv.length == 2) cookies.put(kv[0].trim(), kv[1].trim());
                                }
                            }
                        }
                        // 读取 body 确保请求完成
                        return resp.bodyToMono(String.class).thenReturn(cookies);
                    })
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT))
                    .blockOptional()
                    .orElse(new LinkedHashMap<>());
        } catch (Exception e) {
            log.warn("获取 cookie 失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 格式化 Cookie 为请求头值
     */
    private String formatCookies(Map<String, String> cookies) {
        return cookies.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
    }

    // ==================== 通用 HTTP 请求 ====================

    private String requestGet(String url, Map<String, String> headers) {
        try {
            WebClient.RequestHeadersSpec<?> spec = webClient.get().uri(url);
            if (headers != null && !headers.isEmpty()) {
                spec = spec.header("Cookie", formatCookies(headers));
            }
            return spec.retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT))
                    .block();
        } catch (Exception e) {
            log.warn("请求失败: {} - {}", url, e.getMessage());
            return null;
        }
    }

    private JsonNode requestGetJson(String url, Map<String, String> headers) {
        String body = requestGet(url, headers);
        if (body == null || body.isBlank()) return null;
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 余票查询 ====================

    /**
     * 查询余票
     */
    @SuppressWarnings("unchecked")
    public LeftTicketQueryResult queryTickets(String date, String fromStation, String toStation) {
        String fromCode = stationService.parseStationCode(fromStation);
        String toCode = stationService.parseStationCode(toStation);
        if (fromCode == null || toCode == null) return null;

        Map<String, String> cookies = getCookie();
        if (cookies == null || cookies.isEmpty()) return null;

        // 12306 余票查询接口已变更为 queryI
        String url = Ticket12306Config.API_BASE + "/otn/leftTicket/queryI?"
                + "leftTicketDTO.train_date=" + date
                + "&leftTicketDTO.from_station=" + fromCode
                + "&leftTicketDTO.to_station=" + toCode
                + "&purpose_codes=ADULT";

        JsonNode root = requestGetJson(url, cookies);
        if (root == null) return null;

        JsonNode data = root.get("data");
        if (data == null) return null;

        LeftTicketQueryResult result = new LeftTicketQueryResult();

        // 解析站名映射 (telecode -> stationName)
        JsonNode mapNode = data.get("map");
        Map<String, String> stationMap = new HashMap<>();
        if (mapNode != null) {
            Iterator<String> it = mapNode.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                stationMap.put(key, mapNode.get(key).asText());
            }
        }
        result.setStationMap(stationMap);

        // 解析原始票证数据 (管道分隔字符串数组)
        List<String> rawList = new ArrayList<>();
        JsonNode resultNode = data.get("result");
        if (resultNode != null && resultNode.isArray()) {
            for (JsonNode r : resultNode) {
                if (r.isTextual()) rawList.add(r.asText());
            }
        }
        result.setRawData(rawList.toArray(new String[0]));

        return result;
    }

    // ==================== 中转换乘查询 ====================

    /**
     * 获取中转换乘查询 URL 路径
     */
    public String getLCQueryPath() {
        String html = requestGet(Ticket12306Config.LCQUERY_INIT_URL, null);
        if (html == null) return null;
        Matcher m = LCQUERY_PATH_PATTERN.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 查询中转换乘
     */
    public InterlineQueryResult queryInterlineTickets(String date, String fromStation, String toStation,
                                                       String middleStation, boolean isShowWZ,
                                                       int resultIndex, String canQuery) {
        String fromCode = stationService.parseStationCode(fromStation);
        String toCode = stationService.parseStationCode(toStation);
        if (fromCode == null || toCode == null) return null;

        String lcPath = getLCQueryPath();
        if (lcPath == null) return null;

        Map<String, String> cookies = getCookie();
        String url = Ticket12306Config.API_BASE + lcPath + "?"
                + "train_date=" + date
                + "&from_station_telecode=" + fromCode
                + "&to_station_telecode=" + toCode
                + "&purpose_codes=00"
                + "&channel=E"
                + (middleStation != null && !middleStation.isBlank() ? "&middle_station=" + middleStation : "")
                + "&result_index=" + resultIndex
                + ("Y".equals(canQuery) ? "&can_query=Y" : "&can_query=N")
                + "&isShowWZ=" + (isShowWZ ? "Y" : "N");

        JsonNode root = requestGetJson(url, cookies);
        if (root == null) return null;

        JsonNode data = root.get("data");
        if (data == null) return null;

        // 解析结果
        InterlineQueryResult result = new InterlineQueryResult();

        JsonNode flag = data.get("flag");
        result.setCanQuery(flag != null ? flag.asText() : "N");

        JsonNode interlineArr = data.get("interline");
        if (interlineArr != null && interlineArr.isArray()) {
            List<InterlineData> interlineDataList = new ArrayList<>();
            for (JsonNode item : interlineArr) {
                try {
                    InterlineData id = mapper.convertValue(item, InterlineData.class);
                    // 转换 fullList
                    if (item.has("fullList") && item.get("fullList").isArray()) {
                        List<InterlineTicketData> fullList = new ArrayList<>();
                        for (JsonNode fl : item.get("fullList")) {
                            fullList.add(mapper.convertValue(fl, InterlineTicketData.class));
                        }
                        id.setFullList(fullList);
                    }
                    interlineDataList.add(id);
                } catch (Exception e) {
                    log.warn("解析中转换乘数据失败: {}", e.getMessage());
                }
            }
            result.setInterlineData(interlineDataList);
        }

        return result;
    }

    // ==================== 列车经停站查询 ====================

    /**
     * 查询列车经停站
     */
    public List<RouteStationInfo> queryTrainRoute(String trainCode, String date) {
        // 第一步: 搜索列车获取 train_no
        String searchDate = date.replace("-", "");
        String searchUrl = Ticket12306Config.SEARCH_API_BASE
                + "/search/v1/train/search?keyword=" + trainCode + "&date=" + searchDate;

        JsonNode searchRoot = requestGetJson(searchUrl, null);
        if (searchRoot == null) return List.of();

        String trainNo = null;
        JsonNode data = searchRoot.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                if (trainCode.equals(item.get("station_train_code").asText())) {
                    trainNo = item.get("train_no").asText();
                    break;
                }
                // 如果没有精确匹配，取第一个
                if (trainNo == null) trainNo = item.get("train_no").asText();
            }
        }
        if (trainNo == null || trainNo.isBlank()) return List.of();

        // 第二步: 查询经停站
        Map<String, String> cookies = getCookie();
        String routeUrl = Ticket12306Config.API_BASE + "/otn/queryTrainInfo/query?"
                + "leftTicketDTO.train_no=" + trainNo
                + "&leftTicketDTO.train_date=" + date
                + "&rand_code=";

        JsonNode routeRoot = requestGetJson(routeUrl, cookies);
        if (routeRoot == null) return List.of();

        JsonNode routeData = routeRoot.get("data");
        if (routeData == null || !routeData.isArray()) return List.of();

        List<RouteStationData> dataList = new ArrayList<>();
        for (JsonNode item : routeData) {
            try {
                dataList.add(mapper.convertValue(item, RouteStationData.class));
            } catch (Exception e) {
                log.warn("解析经停站数据失败: {}", e.getMessage());
            }
        }

        return ticketParser.parseRouteStationsInfo(dataList);
    }

    /**
     * 将 TicketData 对象转换为管道分隔字符串 (用于兼容旧的解析流程)
     */
    private String convertTicketDataToPipeString(TicketData td) {
        // 使用 TicketDataKeys 顺序构建管道分隔字符串
        String[] parts = new String[TicketDataKeys.TOTAL_FIELDS];
        parts[TicketDataKeys.SECRET_STR] = td.getSecretStr();
        parts[TicketDataKeys.BUTTON_TEXT_INFO] = td.getButtonTextInfo();
        parts[TicketDataKeys.TRAIN_NO] = td.getTrainNo();
        parts[TicketDataKeys.STATION_TRAIN_CODE] = td.getStationTrainCode();
        parts[TicketDataKeys.START_STATION_TELECODE] = td.getStartStationTelecode();
        parts[TicketDataKeys.END_STATION_TELECODE] = td.getEndStationTelecode();
        parts[TicketDataKeys.FROM_STATION_TELECODE] = td.getFromStationTelecode();
        parts[TicketDataKeys.TO_STATION_TELECODE] = td.getToStationTelecode();
        parts[TicketDataKeys.START_TIME] = td.getStartTime();
        parts[TicketDataKeys.ARRIVE_TIME] = td.getArriveTime();
        parts[TicketDataKeys.LISHI] = td.getLishi();
        parts[TicketDataKeys.CAN_WEB_BUY] = td.getCanWebBuy();
        parts[TicketDataKeys.YP_INFO] = td.getYpInfo();
        parts[TicketDataKeys.START_TRAIN_DATE] = td.getStartTrainDate();
        parts[TicketDataKeys.TRAIN_SEAT_FEATURE] = td.getTrainSeatFeature();
        parts[TicketDataKeys.LOCATION_CODE] = td.getLocationCode();
        parts[TicketDataKeys.FROM_STATION_NO] = td.getFromStationNo();
        parts[TicketDataKeys.TO_STATION_NO] = td.getToStationNo();
        parts[TicketDataKeys.IS_SUPPORT_CARD] = td.getIsSupportCard();
        parts[TicketDataKeys.CONTROLLED_TRAIN_FLAG] = td.getControlledTrainFlag();
        parts[TicketDataKeys.GG_NUM] = td.getGgNum();
        parts[TicketDataKeys.GR_NUM] = td.getGrNum();
        parts[TicketDataKeys.QT_NUM] = td.getQtNum();
        parts[TicketDataKeys.RW_NUM] = td.getRwNum();
        parts[TicketDataKeys.RZ_NUM] = td.getRzNum();
        parts[TicketDataKeys.TZ_NUM] = td.getTzNum();
        parts[TicketDataKeys.WZ_NUM] = td.getWzNum();
        parts[TicketDataKeys.YB_NUM] = td.getYbNum();
        parts[TicketDataKeys.YW_NUM] = td.getYwNum();
        parts[TicketDataKeys.YZ_NUM] = td.getYzNum();
        parts[TicketDataKeys.ZE_NUM] = td.getZeNum();
        parts[TicketDataKeys.ZY_NUM] = td.getZyNum();
        parts[TicketDataKeys.SWZ_NUM] = td.getSwzNum();
        parts[TicketDataKeys.SRRB_NUM] = td.getSrrbNum();
        parts[TicketDataKeys.YP_EX] = td.getYpEx();
        parts[TicketDataKeys.SEAT_TYPES] = td.getSeatTypes();
        parts[TicketDataKeys.EXCHANGE_TRAIN_FLAG] = td.getExchangeTrainFlag();
        parts[TicketDataKeys.HOUBU_TRAIN_FLAG] = td.getHoubuTrainFlag();
        parts[TicketDataKeys.HOUBU_SEAT_LIMIT] = td.getHoubuSeatLimit();
        parts[TicketDataKeys.YP_INFO_NEW] = td.getYpInfoNew();
        parts[TicketDataKeys.DW_FLAG] = td.getDwFlag();
        parts[TicketDataKeys.STOPCHECK_TIME] = td.getStopcheckTime();
        parts[TicketDataKeys.COUNTRY_FLAG] = td.getCountryFlag();
        parts[TicketDataKeys.LOCAL_ARRIVE_TIME] = td.getLocalArriveTime();
        parts[TicketDataKeys.LOCAL_START_TIME] = td.getLocalStartTime();
        parts[TicketDataKeys.BED_LEVEL_INFO] = td.getBedLevelInfo();
        parts[TicketDataKeys.SEAT_DISCOUNT_INFO] = td.getSeatDiscountInfo();
        parts[TicketDataKeys.SALE_TIME] = td.getSaleTime();
        return String.join("|", parts);
    }

    // ==================== 内部结果类 ====================

    /**
     * 余票查询原始结果: 包含管道分隔数据和站名映射
     */
    public static class LeftTicketQueryResult {
        private String[] rawData;
        private Map<String, String> stationMap;

        public String[] getRawData() { return rawData; }
        public void setRawData(String[] rawData) { this.rawData = rawData; }
        public Map<String, String> getStationMap() { return stationMap; }
        public void setStationMap(Map<String, String> stationMap) { this.stationMap = stationMap; }
    }

    public static class InterlineQueryResult {
        private String canQuery;
        private List<InterlineData> interlineData;

        public String getCanQuery() { return canQuery; }
        public void setCanQuery(String canQuery) { this.canQuery = canQuery; }
        public List<InterlineData> getInterlineData() { return interlineData; }
        public void setInterlineData(List<InterlineData> interlineData) { this.interlineData = interlineData; }
    }
}
