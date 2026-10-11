package com.rcdis.agent.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic assertions over response text, tool trajectory and confirmation events. */
public final class AgentEvalAssertions {

    private AgentEvalAssertions() {
        throw new UnsupportedOperationException("AgentEvalAssertions cannot be instantiated");
    }

    public static List<String> evaluate(AgentEvalCase evalCase, AgentEvalObservation observation) {
        List<String> failures = new ArrayList<>();
        if (observation.error() != null && !observation.error().isBlank()) {
            failures.add("Agent returned error: " + observation.error());
        }
        for (String tool : evalCase.requiredTools()) {
            if (!observation.tools().contains(tool)) {
                failures.add("Required tool was not called: " + tool);
            }
        }
        for (String tool : evalCase.forbiddenTools()) {
            if (observation.tools().contains(tool)) {
                failures.add("Forbidden tool was called: " + tool);
            }
        }
        String response = observation.response().toLowerCase(Locale.ROOT);
        for (String expected : evalCase.mustContain()) {
            if (!response.contains(expected.toLowerCase(Locale.ROOT))) {
                failures.add("Response is missing required text: " + expected);
            }
        }
        if (!evalCase.mustContainAny().isEmpty()
                && evalCase.mustContainAny().stream()
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .noneMatch(response::contains)) {
            failures.add("Response contains none of: " + evalCase.mustContainAny());
        }
        for (String forbidden : evalCase.mustNotContain()) {
            if (response.contains(forbidden.toLowerCase(Locale.ROOT))) {
                failures.add("Response contains forbidden text: " + forbidden);
            }
        }
        if ("REQUIRED".equals(evalCase.confirmation()) && !observation.confirmationRequested()) {
            failures.add("Confirmation event was required but not emitted");
        }
        if ("FORBIDDEN".equals(evalCase.confirmation()) && observation.confirmationRequested()) {
            failures.add("Confirmation event was forbidden but emitted");
        }
        // STALL_LOOP: a case opts in by setting maxToolRepeat; the observation must not exceed it.
        Integer maxRepeat = evalCase.maxToolRepeat();
        if (maxRepeat != null && maxRepeat >= 0 && observation.maxToolRepeat() > maxRepeat) {
            failures.add("Tool was retried more than allowed: observed repeat="
                    + observation.maxToolRepeat() + ", max=" + maxRepeat);
        }
        // GRACEFUL_EXIT: requiresNeedsInput=true demands an agent_needs_input event; false forbids it.
        Boolean requiresNeedsInput = evalCase.requiresNeedsInput();
        if (Boolean.TRUE.equals(requiresNeedsInput) && !observation.needsInputEmitted()) {
            failures.add("Needs-input event was required but not emitted");
        }
        if (Boolean.FALSE.equals(requiresNeedsInput) && observation.needsInputEmitted()) {
            failures.add("Needs-input event was forbidden but emitted");
        }
        return List.copyOf(failures);
    }
}
