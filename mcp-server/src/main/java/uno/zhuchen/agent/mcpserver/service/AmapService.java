package uno.zhuchen.agent.mcpserver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import uno.zhuchen.agent.mcpserver.config.AmapConfig;

import java.net.URI;
import java.util.Map;

/**
 * 高德地图 REST API 调用服务
 *
 * <p>封装驾车路线规划、POI 搜索、天气查询三个接口。
 * 对 LLM 友好的简化参数在内部映射为高德 API 所需的编码。
 */
@Service
public class AmapService {

    private static final Logger log = LoggerFactory.getLogger(AmapService.class);

    private static final String BASE_V5 = "https://restapi.amap.com/v5";
    private static final String BASE_V3 = "https://restapi.amap.com/v3";

    /** 算路策略：中文描述 → 高德 API 数字编码 */
    private static final Map<String, String> DRIVING_STRATEGY = Map.of(
            "推荐", "32",
            "速度最快", "38",
            "高速优先", "34",
            "不走高速", "35",
            "避开拥堵", "33",
            "费用优先", "1"
    );

    /** 常见城市名 → citycode 映射（公交规划要求 citycode 格式） */
    private static final Map<String, String> CITY_TO_CITYCODE = Map.ofEntries(
            Map.entry("北京", "010"),
            Map.entry("上海", "021"),
            Map.entry("广州", "020"),
            Map.entry("深圳", "0755"),
            Map.entry("成都", "028"),
            Map.entry("杭州", "0571"),
            Map.entry("武汉", "027"),
            Map.entry("南京", "025"),
            Map.entry("重庆", "023"),
            Map.entry("天津", "022"),
            Map.entry("西安", "029"),
            Map.entry("苏州", "0512"),
            Map.entry("长沙", "0731"),
            Map.entry("郑州", "0371"),
            Map.entry("青岛", "0532"),
            Map.entry("大连", "0411"),
            Map.entry("厦门", "0592"),
            Map.entry("宁波", "0574"),
            Map.entry("昆明", "0871"),
            Map.entry("沈阳", "024"),
            Map.entry("济南", "0531"),
            Map.entry("合肥", "0551"),
            Map.entry("福州", "0591"),
            Map.entry("哈尔滨", "0451"),
            Map.entry("贵阳", "0851"),
            Map.entry("海口", "0898"),
            Map.entry("拉萨", "0891"),
            Map.entry("乌鲁木齐", "0991"),
            Map.entry("呼和浩特", "0471"),
            Map.entry("南宁", "0771"),
            Map.entry("南昌", "0791"),
            Map.entry("太原", "0351"),
            Map.entry("石家庄", "0311"),
            Map.entry("兰州", "0931")
    );

    private final WebClient webClient;
    private final AmapConfig config;

    public AmapService(AmapConfig config) {
        this.config = config;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    // ==================== 路线规划 (v5) ====================

    /**
     * 驾车路线规划
     *
     * @param origin      起点经纬度 "经度,纬度"
     * @param destination 终点经纬度 "经度,纬度"
     * @param strategy    算路偏好中文描述：推荐 / 高速优先 / 不走高速 / 避开拥堵 / 速度最快 / 费用优先
     * @return 高德原始 JSON 响应
     */
    public String drivingRoute(String origin, String destination, String strategy) {
        String strategyCode = DRIVING_STRATEGY.getOrDefault(strategy, "32");

        String url = UriComponentsBuilder.fromHttpUrl(BASE_V5 + "/direction/driving")
                .queryParam("key", config.getKey())
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .queryParam("strategy", strategyCode)
                .build()
                .toUriString();

        log.debug("驾车路线规划请求: origin={}, destination={}, strategy={}→{}",
                origin, destination, strategy, strategyCode);
        return callApi(url);
    }

    /**
     * 步行路线规划
     */
    public String walkingRoute(String origin, String destination) {
        String url = UriComponentsBuilder.fromHttpUrl(BASE_V5 + "/direction/walking")
                .queryParam("key", config.getKey())
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .build()
                .toUriString();

        log.debug("步行路线规划请求: origin={}, destination={}", origin, destination);
        return callApi(url);
    }

    /**
     * 骑行路线规划
     */
    public String bicyclingRoute(String origin, String destination) {
        String url = UriComponentsBuilder.fromHttpUrl(BASE_V5 + "/direction/bicycling")
                .queryParam("key", config.getKey())
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .build()
                .toUriString();

        log.debug("骑行路线规划请求: origin={}, destination={}", origin, destination);
        return callApi(url);
    }

    /**
     * 公交路线规划
     *
     * @param origin      起点经纬度
     * @param destination 终点经纬度
     * @param city        城市（中文名/citycode/adcode），中转和终到同城
     * @return 高德原始 JSON 响应
     */
    public String transitRoute(String origin, String destination, String city) {
        String cityCode = resolveCityCode(city);

        String url = UriComponentsBuilder.fromHttpUrl(BASE_V5 + "/direction/transit/integrated")
                .queryParam("key", config.getKey())
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .queryParam("city1", cityCode)
                .queryParam("city2", cityCode)
                .build()
                .toUriString();

        log.debug("公交路线规划请求: origin={}, destination={}, city={}→{}",
                origin, destination, city, cityCode);
        return callApi(url);
    }

    // ==================== POI 搜索 (v5) ====================

    /**
     * POI 关键词搜索
     *
     * @param keywords 查询关键词
     * @param city     城市（可选，中文名/citycode/adcode）
     * @return 高德原始 JSON 响应
     */
    public String poiTextSearch(String keywords, String city) {
        String url = UriComponentsBuilder.fromHttpUrl(BASE_V5 + "/place/text")
                .queryParam("key", config.getKey())
                .queryParam("keywords", keywords)
                .queryParamIfPresent("city", java.util.Optional.ofNullable(city))
                .queryParam("offset", 10)
                .queryParam("page", 1)
                .build()
                .toUriString();

        log.debug("POI文本搜索请求: keywords={}, city={}", keywords, city);
        return callApi(url);
    }

    /**
     * POI 周边搜索
     *
     * @param location 中心点坐标 "经度,纬度"
     * @param keywords 查询关键词（可选）
     * @param radius   搜索半径，米（默认 1000）
     * @return 高德原始 JSON 响应
     */
    public String poiAroundSearch(String location, String keywords, Integer radius) {
        String url = UriComponentsBuilder.fromHttpUrl(BASE_V5 + "/place/around")
                .queryParam("key", config.getKey())
                .queryParam("location", location)
                .queryParamIfPresent("keywords", java.util.Optional.ofNullable(keywords))
                .queryParam("radius", radius != null ? radius : 1000)
                .build()
                .toUriString();

        log.debug("POI周边搜索请求: location={}, keywords={}, radius={}", location, keywords, radius);
        return callApi(url);
    }

    // ==================== 天气查询 (v3) ====================

    /**
     * 实时天气查询
     *
     * @param city 城市 adcode 编码
     * @return 高德原始 JSON 响应（实时天气）
     */
    public String weatherQuery(String city) {
        String url = UriComponentsBuilder.fromHttpUrl(BASE_V3 + "/weather/weatherInfo")
                .queryParam("key", config.getKey())
                .queryParam("city", city)
                .queryParam("extensions", "base")
                .queryParam("output", "JSON")
                .build()
                .toUriString();

        log.debug("天气查询请求: city={}", city);
        return callApi(url);
    }

    // ==================== 地理编码 (v3) ====================

    /**
     * 地理编码 — 将地址/地名解析为经纬度坐标
     *
     * @param address 地址描述，如 北京市天安门、广东省广州市
     * @param city    所在城市（可选，提高精确度）
     * @return 高德原始 JSON 响应
     */
    public String geocode(String address, String city) {
        String url = UriComponentsBuilder.fromHttpUrl(BASE_V3 + "/geocode/geo")
                .queryParam("key", config.getKey())
                .queryParam("address", address)
                .queryParamIfPresent("city", java.util.Optional.ofNullable(city))
                .queryParam("output", "JSON")
                .build()
                .toUriString();

        log.debug("地理编码请求: address={}, city={}", address, city);
        return callApi(url);
    }

    /**
     * 逆地理编码 — 将经纬度坐标解析为详细地址
     *
     * @param location 经纬度坐标 "经度,纬度"
     * @return 高德原始 JSON 响应
     */
    public String reverseGeocode(String location) {
        String url = UriComponentsBuilder.fromHttpUrl(BASE_V3 + "/geocode/regeo")
                .queryParam("key", config.getKey())
                .queryParam("location", location)
                .queryParam("output", "JSON")
                .build()
                .toUriString();

        log.debug("逆地理编码请求: location={}", location);
        return callApi(url);
    }

    // ==================== 工具方法 ====================

    /**
     * 将城市名/citycode/adcode 统一解析为 citycode 格式
     */
    private String resolveCityCode(String city) {
        if (city == null) {
            return "010"; // 默认北京
        }
        // 如果已经是纯数字格式（citycode 或 adcode），直接返回
        if (city.matches("\\d+")) {
            return city;
        }
        // 从映射表查找中文城市名
        String code = CITY_TO_CITYCODE.get(city);
        if (code != null) {
            return code;
        }
        // 尝试去除"市"后缀再查
        if (city.endsWith("市")) {
            code = CITY_TO_CITYCODE.get(city.substring(0, city.length() - 1));
            if (code != null) {
                return code;
            }
        }
        // 查不到就原样返回，让高德 API 自己处理
        log.warn("未找到城市 [{}] 的 citycode，直接透传", city);
        return city;
    }

    // ==================== 底层 HTTP 调用 ====================

    private String callApi(String url) {
        try {
            URI uri = URI.create(url);
            String body = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (body == null) {
                return "{\"error\":\"高德 API 返回空响应\"}";
            }
            return body;
        } catch (Exception e) {
            log.error("高德 API 调用失败: {}", e.getMessage());
            return String.format("{\"error\":\"高德 API 调用失败: %s\"}", e.getMessage());
        }
    }
}
