package uno.zhuchen.agent.core.memory;

import java.util.List;

public interface MemoryCandidateExtractor {

    List<MemoryExtractionResult> extract(String payloadJson);

    /** 抽取器版本号, 写入 memory_item.extractor_version 便于事后归因 */
    default String version() {
        return "rule-v1";
    }
}
