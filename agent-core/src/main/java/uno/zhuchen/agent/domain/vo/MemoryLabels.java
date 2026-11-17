package uno.zhuchen.agent.domain.vo;

import java.util.Map;

public final class MemoryLabels {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("travel.preference.food", "偏好：美食"),
            Map.entry("travel.preference.nature", "偏好：自然"),
            Map.entry("travel.preference.culture", "偏好：文化"),
            Map.entry("travel.preference.history", "偏好：历史"),
            Map.entry("travel.preference.shopping", "偏好：购物"),
            Map.entry("travel.preference.outdoor", "偏好：户外"),
            Map.entry("travel.preference.family", "偏好：亲子"),
            Map.entry("travel.default.home_city", "常用出发城市"),
            Map.entry("travel.default.budget", "常用预算"),
            Map.entry("travel.default.companions", "常用出行人数"),
            Map.entry("travel.profile.diet_no_spicy", "饮食偏好")
    );

    private MemoryLabels() {
    }

    public static String labelFor(String key) {
        return LABELS.getOrDefault(key, key);
    }
}
