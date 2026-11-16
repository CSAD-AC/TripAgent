package uno.zhuchen.agent.tools.amap.service;

import org.junit.jupiter.api.Test;
import uno.zhuchen.agent.tools.amap.config.AmapConfig;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AmapServiceTest {

    @Test
    void poiTextSearchEncodesSpacesPipesAndChineseCharacters() {
        AmapConfig config = new AmapConfig();
        config.setKey("test-key");
        AmapService service = new AmapService(config);

        String url = service.buildPoiTextSearchUrl("火锅 川菜|小吃", "成都");

        assertThatCode(() -> URI.create(url)).doesNotThrowAnyException();
        assertThat(url)
                .doesNotContain(" ")
                .contains("%7C")
                .contains("%E7%81%AB%E9%94%85");
    }
}
