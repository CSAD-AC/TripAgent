package uno.zhuchen.agent.core.memory;

import java.util.List;

public interface MemoryCandidateExtractor {

    List<MemoryExtractionResult> extract(String payloadJson);
}
