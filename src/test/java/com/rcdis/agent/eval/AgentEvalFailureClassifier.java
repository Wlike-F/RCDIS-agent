package com.rcdis.agent.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps deterministic assertion failure messages produced by {@link AgentEvalAssertions} to
 * root-cause buckets. Purely rule-based on message prefixes so classification stays repeatable.
 */
public final class AgentEvalFailureClassifier {

    private AgentEvalFailureClassifier() {
        throw new UnsupportedOperationException("AgentEvalFailureClassifier cannot be instantiated");
    }

    /** One classified failure with the original message and the tool it names, if any. */
    public record ClassifiedFailure(AgentEvalFailureType type, String message, String tool) {
    }

    public static List<ClassifiedFailure> classify(List<String> failures) {
        if (failures == null || failures.isEmpty()) {
            return List.of();
        }
        List<ClassifiedFailure> classified = new ArrayList<>();
        for (String failure : failures) {
            AgentEvalFailureType type = typeOf(failure);
            classified.add(new ClassifiedFailure(type, failure, namedTool(type, failure)));
        }
        return List.copyOf(classified);
    }

    private static AgentEvalFailureType typeOf(String failure) {
        String message = failure.toLowerCase(Locale.ROOT);
        if (message.startsWith("agent returned error")) {
            return AgentEvalFailureType.RUNTIME_ERROR;
        }
        if (message.startsWith("required tool was not called")) {
            return AgentEvalFailureType.TOOL_OMITTED;
        }
        if (message.startsWith("forbidden tool was called")) {
            return AgentEvalFailureType.TOOL_FORBIDDEN_INVOKED;
        }
        if (message.startsWith("confirmation event")) {
            return AgentEvalFailureType.CONFIRMATION_GATE;
        }
        if (message.startsWith("response is missing required text")
                || message.startsWith("response contains none of")
                || message.startsWith("response contains forbidden text")) {
            return AgentEvalFailureType.FACT_INCONSISTENCY;
        }
        return AgentEvalFailureType.UNKNOWN;
    }

    private static String namedTool(AgentEvalFailureType type, String failure) {
        if (type != AgentEvalFailureType.TOOL_OMITTED && type != AgentEvalFailureType.TOOL_FORBIDDEN_INVOKED) {
            return null;
        }
        int index = failure.indexOf(": ");
        return index < 0 ? null : failure.substring(index + 2).trim();
    }
}
