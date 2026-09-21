package com.rcdis.agent.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Data-driven mapping from failure root causes to tunable system prompt sections plus the
 * suggestion wording shown in the tuning report. Kept in a resource file so the mapping can be
 * maintained without touching code.
 */
public final class AgentEvalTuningMap {

    public static final String RESOURCE = "agent-eval/tuning-map.json";

    private final List<TuningEntry> entries;

    private AgentEvalTuningMap(List<TuningEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** One mapping row: a failure type (optionally narrowed by case categories) to prompt sections. */
    public record TuningEntry(String failureType, List<String> categories, List<String> tunableIds, String hint) {

        public TuningEntry {
            categories = categories == null ? List.of() : List.copyOf(categories);
            tunableIds = tunableIds == null ? List.of() : List.copyOf(tunableIds);
        }
    }

    private record MapFile(List<TuningEntry> entries) {

        private MapFile {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    public static AgentEvalTuningMap load(ObjectMapper objectMapper) {
        InputStream stream = AgentEvalTuningMap.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Agent eval tuning map not found: " + RESOURCE);
        }
        try (InputStream in = stream) {
            return new AgentEvalTuningMap(objectMapper.readValue(in, MapFile.class).entries());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read Agent eval tuning map: " + RESOURCE, exception);
        }
    }

    /**
     * First entry whose failure type matches and whose category filter accepts the given case
     * category. Category-specific entries must be listed before the catch-all row of the same type.
     */
    public Optional<TuningEntry> find(AgentEvalFailureType type, String category) {
        String normalized = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        return entries.stream()
                .filter(entry -> type.name().equals(entry.failureType()))
                .filter(entry -> entry.categories().isEmpty() || entry.categories().contains(normalized))
                .findFirst();
    }

    public List<TuningEntry> entries() {
        return entries;
    }
}
