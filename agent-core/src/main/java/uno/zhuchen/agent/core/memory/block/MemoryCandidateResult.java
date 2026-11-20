package uno.zhuchen.agent.core.memory.block;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

public record MemoryCandidateResult(
        List<String> profileCandidates,
        List<String> eventCandidates,
        List<String> discarded
) {
    public MemoryCandidateResult {
        profileCandidates = profileCandidates == null ? List.of() : List.copyOf(profileCandidates);
        eventCandidates = eventCandidates == null ? List.of() : List.copyOf(eventCandidates);
        discarded = discarded == null ? List.of() : List.copyOf(discarded);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return profileCandidates.isEmpty() && eventCandidates.isEmpty();
    }
}
