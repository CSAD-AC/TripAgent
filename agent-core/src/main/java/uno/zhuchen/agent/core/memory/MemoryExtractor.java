package uno.zhuchen.agent.core.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MemoryExtractor {

    private static final Map<Pattern, MemoryCandidate> PREFERENCE_PATTERNS = preferencePatterns();
    private static final Pattern HOME_CITY = Pattern.compile(
            "(?:我来自|我的家乡是|我老家是|我现在住在|我经常从)\\s*([\\u4e00-\\u9fa5]{2,8}?)(?:市)?[，,。；;\\s]|"
                    + "(?:我来自|我的家乡是|我老家是|我现在住在|我经常从)\\s*([\\u4e00-\\u9fa5]{2,8}?)(?:市)?$");
    private static final Pattern USUAL_BUDGET = Pattern.compile(
            "(?:我)?(?:通常|一般|平时)?预算(?:大概|大约|通常|一般是)?\\s*(\\d{3,6})\\s*元?");
    private static final Pattern USUAL_COMPANIONS = Pattern.compile(
            "(?:我)?(?:通常|一般|平时)?(?:几个人|人数)?(?:一般是|通常是)?\\s*(\\d+|[一二三四五六七八九十两]+)\\s*(?:个人|人|位)");
    private static final Pattern NO_SPICY = Pattern.compile("(?:我)?(?:不(?:吃|能|喜欢)|忌)\\s*辣");

    public List<MemoryCandidate> extract(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return List.of();
        }

        Map<String, MemoryCandidate> candidates = new LinkedHashMap<>();
        for (Map.Entry<Pattern, MemoryCandidate> entry : PREFERENCE_PATTERNS.entrySet()) {
            Matcher matcher = entry.getKey().matcher(userMessage);
            if (matcher.find()) {
                MemoryCandidate candidate = entry.getValue();
                candidates.put(candidate.key(), candidate);
            }
        }

        Matcher homeCity = HOME_CITY.matcher(userMessage);
        if (homeCity.find()) {
            String city = homeCity.group(1) != null ? homeCity.group(1) : homeCity.group(2);
            String value = normalizeCity(city);
            add(candidates, new MemoryCandidate("default", "travel.default.home_city", value));
        }

        Matcher budget = USUAL_BUDGET.matcher(userMessage);
        if (budget.find()) {
            add(candidates, new MemoryCandidate(
                    "default", "travel.default.budget", budget.group(1)));
        }

        Matcher companions = USUAL_COMPANIONS.matcher(userMessage);
        if (companions.find()) {
            String value = normalizeNumber(companions.group(1));
            add(candidates, new MemoryCandidate(
                    "default", "travel.default.companions", value));
        }

        if (NO_SPICY.matcher(userMessage).find()) {
            add(candidates, new MemoryCandidate(
                    "profile", "travel.profile.diet_no_spicy", "不吃辣"));
        }

        return List.copyOf(new ArrayList<>(candidates.values()));
    }

    private static void add(Map<String, MemoryCandidate> candidates, MemoryCandidate candidate) {
        candidates.put(candidate.key(), candidate);
    }

    private static String normalizeCity(String city) {
        String value = city.trim();
        return value.endsWith("市") ? value.substring(0, value.length() - 1) : value;
    }

    private static String normalizeNumber(String value) {
        return switch (value) {
            case "两" -> "2";
            case "一" -> "1";
            case "三" -> "3";
            case "四" -> "4";
            case "五" -> "5";
            case "六" -> "6";
            case "七" -> "7";
            case "八" -> "8";
            case "九" -> "9";
            case "十" -> "10";
            default -> value;
        };
    }

    private static Map<Pattern, MemoryCandidate> preferencePatterns() {
        Map<Pattern, MemoryCandidate> patterns = new LinkedHashMap<>();
        patterns.put(Pattern.compile("(?:我)?(?:喜欢|偏好|爱|更喜欢)[^。！？；,，]{0,8}美食"),
                new MemoryCandidate("preference", "travel.preference.food", "true"));
        patterns.put(Pattern.compile("(?:我)?(?:喜欢|偏好|爱|更喜欢)[^。！？；,，]{0,8}(?:自然|自然风光|风景)"),
                new MemoryCandidate("preference", "travel.preference.nature", "true"));
        patterns.put(Pattern.compile("(?:我)?(?:喜欢|偏好|爱|更喜欢)[^。！？；,，]{0,8}文化"),
                new MemoryCandidate("preference", "travel.preference.culture", "true"));
        patterns.put(Pattern.compile("(?:我)?(?:喜欢|偏好|爱|更喜欢)[^。！？；,，]{0,8}历史"),
                new MemoryCandidate("preference", "travel.preference.history", "true"));
        patterns.put(Pattern.compile("(?:我)?(?:喜欢|偏好|爱|更喜欢)[^。！？；,，]{0,8}购物"),
                new MemoryCandidate("preference", "travel.preference.shopping", "true"));
        patterns.put(Pattern.compile("(?:我)?(?:喜欢|偏好|爱|更喜欢)[^。！？；,，]{0,8}(?:户外|徒步|登山|爬山)"),
                new MemoryCandidate("preference", "travel.preference.outdoor", "true"));
        patterns.put(Pattern.compile("(?:我)?(?:喜欢|偏好|爱|更喜欢)[^。！？；,，]{0,8}(?:亲子|带孩子|带小孩)"),
                new MemoryCandidate("preference", "travel.preference.family", "true"));
        return patterns;
    }
}
