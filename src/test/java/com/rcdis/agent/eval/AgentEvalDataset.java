package com.rcdis.agent.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Loads and validates the JSONL evaluation corpus. */
public final class AgentEvalDataset {

    public static final String RESOURCE = "agent-eval/cases.jsonl";
    private static final Set<String> CONFIRMATION_VALUES = Set.of("REQUIRED", "FORBIDDEN", "OPTIONAL");

    private AgentEvalDataset() {
        throw new UnsupportedOperationException("AgentEvalDataset cannot be instantiated");
    }

    public static List<AgentEvalCase> load(ObjectMapper objectMapper) {
        InputStream stream = AgentEvalDataset.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Agent evaluation dataset not found: " + RESOURCE);
        }
        List<AgentEvalCase> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String normalized = line.trim();
                if (normalized.isEmpty() || normalized.startsWith("#")) {
                    continue;
                }
                try {
                    cases.add(objectMapper.readValue(normalized, AgentEvalCase.class));
                } catch (IOException exception) {
                    throw new IllegalStateException("Invalid Agent eval JSONL at line " + lineNumber, exception);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read Agent evaluation dataset", exception);
        }
        validate(cases);
        return List.copyOf(cases);
    }

    public static void validate(List<AgentEvalCase> cases) {
        if (cases == null || cases.size() < 50) {
            throw new IllegalStateException("Agent evaluation dataset must contain at least 50 cases");
        }
        Set<String> ids = new HashSet<>();
        for (AgentEvalCase evalCase : cases) {
            requireText(evalCase.id(), "id");
            requireText(evalCase.category(), "category for " + evalCase.id());
            requireText(evalCase.riskLevel(), "riskLevel for " + evalCase.id());
            requireText(evalCase.input(), "input for " + evalCase.id());
            if (!ids.add(evalCase.id())) {
                throw new IllegalStateException("Duplicate Agent eval case id: " + evalCase.id());
            }
            if (!CONFIRMATION_VALUES.contains(evalCase.confirmation())) {
                throw new IllegalStateException("Invalid confirmation mode for " + evalCase.id()
                        + ": " + evalCase.confirmation());
            }
            Set<String> overlap = new HashSet<>(evalCase.requiredTools());
            overlap.retainAll(evalCase.forbiddenTools());
            if (!overlap.isEmpty()) {
                throw new IllegalStateException("Tools cannot be both required and forbidden for "
                        + evalCase.id() + ": " + overlap);
            }
            for (Map.Entry<String, String> variable : evalCase.variables().entrySet()) {
                requireText(variable.getKey(), "variable name for " + evalCase.id());
                requireText(variable.getValue(), "system property for " + evalCase.id());
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Agent evaluation field is blank: " + field);
        }
    }
}
