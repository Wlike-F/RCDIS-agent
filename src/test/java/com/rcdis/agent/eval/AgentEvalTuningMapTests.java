package com.rcdis.agent.eval;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvalTuningMapTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void everyFailureTypeHasAMatchingEntry() {
        AgentEvalTuningMap map = AgentEvalTuningMap.load(objectMapper);
        for (AgentEvalFailureType type : AgentEvalFailureType.values()) {
            assertThat(map.find(type, "WHATEVER"))
                    .as("tuning map must cover " + type)
                    .isPresent();
        }
    }

    @Test
    void categorySpecificEntryWinsOverCatchAll() {
        AgentEvalTuningMap map = AgentEvalTuningMap.load(objectMapper);
        assertThat(map.find(AgentEvalFailureType.FACT_INCONSISTENCY, "SCOPE_SAFETY"))
                .map(AgentEvalTuningMap.TuningEntry::tunableIds)
                .hasValue(List.of("business-boundary"));
        assertThat(map.find(AgentEvalFailureType.FACT_INCONSISTENCY, "HALLUCINATION"))
                .map(AgentEvalTuningMap.TuningEntry::tunableIds)
                .hasValue(List.of("data-grounding"));
    }

    @Test
    void referencedTunableIdsExistAsAnchoredSectionsInSystemPrompt() {
        AgentEvalTuningMap map = AgentEvalTuningMap.load(objectMapper);
        Map<String, AgentEvalPromptAnchor.PromptSection> sections =
                AgentEvalPromptAnchor.load("prompts/agent-system.st").sections();
        Set<String> sectionIds = sections.keySet();
        assertThat(sectionIds).containsExactlyInAnyOrder(
                "business-boundary", "data-grounding", "high-risk-confirmation", "style",
                "safety-compliance", "tool-selection", "write-confirmation");
        for (AgentEvalTuningMap.TuningEntry entry : map.entries()) {
            assertThat(entry.tunableIds())
                    .as("tunable ids of " + entry.failureType())
                    .isSubsetOf(sectionIds);
        }
    }

    @Test
    void promptHashIsStableSha256Hex() {
        String hash = AgentEvalPromptAnchor.load("prompts/agent-system.st").sha256();
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
