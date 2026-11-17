package uno.zhuchen.agent.core.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryExtractorTest {

    private final MemoryExtractor extractor = new MemoryExtractor();

    @Test
    void extractsExplicitPreferences() {
        List<MemoryCandidate> candidates = extractor.extract(
                "我喜欢美食和自然风光，也爱爬山");

        assertTrue(containsKey(candidates, "travel.preference.food"));
        assertTrue(containsKey(candidates, "travel.preference.nature"));
        assertTrue(containsKey(candidates, "travel.preference.outdoor"));
    }

    @Test
    void extractsLowRiskDefaults() {
        List<MemoryCandidate> candidates = extractor.extract(
                "我来自上海市，一般预算5000元，通常两个人出行，不吃辣");

        assertEquals("上海", valueOf(candidates, "travel.default.home_city"));
        assertEquals("5000", valueOf(candidates, "travel.default.budget"));
        assertEquals("2", valueOf(candidates, "travel.default.companions"));
        assertEquals("不吃辣", valueOf(candidates, "travel.profile.diet_no_spicy"));
    }

    @Test
    void ignoresVagueOrSensitiveLookingText() {
        assertTrue(extractor.extract("随便，你决定吧").isEmpty());
        assertTrue(extractor.extract("我叫张三，身份证号是110101199001011234").isEmpty());
    }

    private static boolean containsKey(List<MemoryCandidate> candidates, String key) {
        return candidates.stream().anyMatch(candidate -> key.equals(candidate.key()));
    }

    private static String valueOf(List<MemoryCandidate> candidates, String key) {
        return candidates.stream()
                .filter(candidate -> key.equals(candidate.key()))
                .map(MemoryCandidate::value)
                .findFirst()
                .orElse(null);
    }
}
