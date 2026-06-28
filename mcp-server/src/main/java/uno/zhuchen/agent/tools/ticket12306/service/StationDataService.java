package uno.zhuchen.agent.tools.ticket12306.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import uno.zhuchen.agent.tools.ticket12306.model.StationData;
import uno.zhuchen.agent.tools.ticket12306.model.StationInfo;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StationDataService {

    private static final Logger log = LoggerFactory.getLogger(StationDataService.class);
    private static final String WEB_URL = "https://www.12306.cn/index/";
    private static final Pattern STATION_NAMES_PATTERN = Pattern.compile("var station_names\\s*=\\s*'([^']+)'");
    private static final Pattern JS_PATH_PATTERN = Pattern.compile(".?(/script/core/common/station_name.+?\\.js)");
    private static final String MISSING_STATION_RAW = "@cdd|成  都东|WEI|chengdudong|cdd||1707|成都||";

    private final WebClient webClient;
    private final Map<String, StationData> stations = new ConcurrentHashMap<>();
    private final Map<String, List<StationInfo>> cityStations = new ConcurrentHashMap<>();
    private final Map<String, StationInfo> cityCodes = new ConcurrentHashMap<>();
    private final Map<String, StationInfo> nameStations = new ConcurrentHashMap<>();

    public StationDataService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024)).build();
    }

    @PostConstruct
    public void init() {
        try {
            loadStations();
            log.info("车站数据加载完成：共 {} 个车站", stations.size());
        } catch (Exception e) {
            log.error("车站数据加载失败", e);
        }
    }

    private void loadStations() {
        String html = fetchString(WEB_URL);
        Matcher m = JS_PATH_PATTERN.matcher(html);
        if (!m.find()) throw new IllegalStateException("无法提取车站 JS 路径");
        String jsPath = m.group(1);
        String jsContent = fetchString("https://www.12306.cn" + jsPath);
        log.info("访问链接为 \"https://www.12306.cn\"{} ", jsPath);


        Matcher sm = STATION_NAMES_PATTERN.matcher(jsContent);
        if (!sm.find()) throw new IllegalStateException("无法提取车站名称数据");
        String rawData = sm.group(1);

        String[] arr = rawData.split("\\|", -1);
        Map<String, StationData> parsed = new LinkedHashMap<>();
        for (int i = 0; i < arr.length / 10; i++) {
            String[] g = Arrays.copyOfRange(arr, i * 10, (i + 1) * 10);
            if (g[2] == null || g[2].isEmpty()) continue;
            StationData s = StationData.builder().stationId(g[0]).stationName(g[1]).stationCode(g[2])
                    .stationPinyin(g[3]).stationShort(g[4]).stationIndex(g[5]).code(g[6]).city(g[7])
                    .r1(g.length > 8 ? g[8] : "").r2(g.length > 9 ? g[9] : "").build();
            parsed.put(s.getStationCode(), s);
        }
        // 补充缺失车站
        String[] ms = MISSING_STATION_RAW.split("\\|", -1);
        if (ms.length >= 8) {
            StationData msd = StationData.builder().stationId(ms[0]).stationName(ms[1]).stationCode(ms[2])
                    .stationPinyin(ms[3]).stationShort(ms[4]).stationIndex(ms[5]).code(ms[6]).city(ms[7]).build();
            parsed.putIfAbsent(msd.getStationCode(), msd);
        }

        stations.putAll(parsed);
        buildIndexes();
    }

    private void buildIndexes() {
        Map<String, List<StationInfo>> cs = new LinkedHashMap<>();
        for (StationData s : stations.values()) {
            cs.computeIfAbsent(s.getCity(), k -> new ArrayList<>()).add(new StationInfo(s.getStationCode(), s.getStationName()));
        }
        cityStations.putAll(cs);

        Map<String, StationInfo> cc = new LinkedHashMap<>();
        for (var e : cs.entrySet()) {
            for (StationInfo si : e.getValue()) {
                if (si.getStationName().equals(e.getKey())) { cc.put(e.getKey(), si); break; }
            }
        }
        cityCodes.putAll(cc);

        Map<String, StationInfo> ns = new LinkedHashMap<>();
        for (StationData s : stations.values()) ns.put(s.getStationName(), new StationInfo(s.getStationCode(), s.getStationName()));
        nameStations.putAll(ns);
    }

    private String fetchString(String url) {
        try { return webClient.get().uri(url).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(15)); }
        catch (Exception e) { throw new RuntimeException("请求失败: " + url, e); }
    }

    public StationData getStationByTelecode(String telecode) { return stations.get(telecode); }
    public List<StationInfo> getStationsInCity(String city) { return cityStations.get(city); }
    public StationInfo getCityCode(String city) { return cityCodes.get(city); }
    public StationInfo getStationByName(String name) { return nameStations.get(name); }

    public String parseStationCode(String station) {
        if (station == null) return null;
        if (station.endsWith("站")) station = station.substring(0, station.length() - 1);
        if (station.matches("^[A-Z]+$") && stations.containsKey(station)) return station;
        StationInfo info = nameStations.get(station);
        return info != null ? info.getStationCode() : null;
    }
}
