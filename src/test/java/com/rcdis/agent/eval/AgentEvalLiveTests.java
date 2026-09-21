package com.rcdis.agent.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Opt-in black-box evaluation against a running RCDIS backend and real model providers.
 *
 * <p>Run with {@code mvn -Dtest=AgentEvalLiveTests -Drcdis.agent.eval.enabled=true test}.
 * The test stays disabled during ordinary builds so CI never spends model tokens unexpectedly.</p>
 */
@EnabledIfSystemProperty(named = "rcdis.agent.eval.enabled", matches = "true")
class AgentEvalLiveTests {

    private static final String DEFAULT_BASE_URL = "http://localhost:8080";
    private static final String DEFAULT_PROVIDERS = "dashscope";
    private static final int DEFAULT_TIMEOUT_SECONDS = 240;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void evaluateConfiguredProvidersAndWriteComparisonReport() {
        String baseUrl = property("rcdis.agent.eval.base-url", DEFAULT_BASE_URL);
        String username = requiredProperty("rcdis.agent.eval.username");
        String password = requiredProperty("rcdis.agent.eval.password");
        List<String> providers = csv(property("rcdis.agent.eval.providers", DEFAULT_PROVIDERS));
        Set<String> caseFilter = Set.copyOf(csv(property("rcdis.agent.eval.cases", "")));
        int timeoutSeconds = Integer.parseInt(property(
                "rcdis.agent.eval.timeout-seconds", String.valueOf(DEFAULT_TIMEOUT_SECONDS)));
        BigDecimal minimumPassRate = new BigDecimal(property("rcdis.agent.eval.minimum-pass-rate", "1.0"));
        Path outputDirectory = Path.of(property("rcdis.agent.eval.output-dir", "target/agent-eval"));
        String promptResource = property("rcdis.agent.eval.prompt-resource", "prompts/agent-system.st");
        Path baselinePath = Path.of(property("rcdis.agent.eval.baseline", ""));

        AgentEvalHttpClient client = new AgentEvalHttpClient(
                objectMapper, baseUrl, Duration.ofSeconds(timeoutSeconds), username, password);
        List<AgentEvalCase> cases = AgentEvalDataset.load(objectMapper).stream()
                .filter(evalCase -> caseFilter.isEmpty() || caseFilter.contains(evalCase.id()))
                .toList();
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("No Agent evaluation cases matched rcdis.agent.eval.cases");
        }

        List<AgentEvalResult> results = new ArrayList<>();
        for (String provider : providers) {
            for (AgentEvalCase evalCase : cases) {
                ResolvedCase resolved = resolve(evalCase);
                if (resolved.missingVariable() != null) {
                    results.add(skipped(evalCase, provider, resolved.missingVariable()));
                    continue;
                }
                AgentEvalObservation observation = client.execute(provider, resolved.evalCase().input());
                List<String> failures = AgentEvalAssertions.evaluate(resolved.evalCase(), observation);
                results.add(new AgentEvalResult(
                        evalCase.id(), evalCase.category(), evalCase.riskLevel(), provider,
                        observation.modelName(), failures.isEmpty() ? "PASSED" : "FAILED",
                        failures, observation));
            }
        }

        AgentEvalReportWriter.write(objectMapper, outputDirectory, results);

        // Version-anchored manifest + human-in-the-loop tuning report; never mutates the prompt.
        AgentEvalPromptAnchor prompt = AgentEvalPromptAnchor.load(promptResource);
        AgentEvalTuningMap tuningMap = AgentEvalTuningMap.load(objectMapper);
        AgentEvalRunManifest manifest = AgentEvalRunManifest.from(
                resolveGitCommit(), prompt.sha256(), promptResource, providers, results);
        AgentEvalRunManifest baseline = loadBaseline(baselinePath);
        AgentEvalTuningReportWriter.write(objectMapper, outputDirectory, manifest, baseline,
                prompt, tuningMap, results);

        List<AgentEvalResult> executed = results.stream()
                .filter(result -> !"SKIPPED".equals(result.status()))
                .toList();
        long passed = executed.stream().filter(result -> "PASSED".equals(result.status())).count();
        BigDecimal passRate = executed.isEmpty()
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(passed).divide(BigDecimal.valueOf(executed.size()), 4, RoundingMode.HALF_UP);

        assertThat(executed)
                .as("At least one Agent eval case must execute; configure required fixture properties")
                .isNotEmpty();
        assertThat(passRate)
                .as("Agent eval pass rate; see %s", outputDirectory.resolve("agent-eval-report.md"))
                .isGreaterThanOrEqualTo(minimumPassRate);
    }

    /** Loads the previous run manifest for baseline comparison; absent or blank means no baseline. */
    private AgentEvalRunManifest loadBaseline(Path baselinePath) {
        if (baselinePath.toString().isBlank() || !Files.exists(baselinePath)) {
            return null;
        }
        try {
            return objectMapper.readValue(baselinePath.toFile(), AgentEvalRunManifest.class);
        } catch (IOException exception) {
            // A corrupt baseline would silently void the regression guard, so fail loudly.
            throw new IllegalStateException("Failed to read Agent eval baseline: " + baselinePath, exception);
        }
    }

    /** Best-effort current commit for the run manifest; returns "unknown" outside a git repo. */
    private static String resolveGitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                return line != null && line.matches("[0-9a-f]{7,40}") ? line : "unknown";
            }
        } catch (IOException exception) {
            return "unknown";
        }
    }

    private ResolvedCase resolve(AgentEvalCase evalCase) {
        String input = evalCase.input();
        List<String> mustContain = evalCase.mustContain();
        List<String> mustContainAny = evalCase.mustContainAny();
        List<String> mustNotContain = evalCase.mustNotContain();
        for (Map.Entry<String, String> variable : evalCase.variables().entrySet()) {
            String value = System.getProperty(variable.getValue());
            if (value == null || value.isBlank()) {
                return new ResolvedCase(null, variable.getValue());
            }
            String marker = "{{" + variable.getKey() + "}}";
            String replacement = value.trim();
            input = input.replace(marker, replacement);
            mustContain = replace(mustContain, marker, replacement);
            mustContainAny = replace(mustContainAny, marker, replacement);
            mustNotContain = replace(mustNotContain, marker, replacement);
        }
        AgentEvalCase resolved = new AgentEvalCase(
                evalCase.id(), evalCase.category(), evalCase.riskLevel(), input,
                evalCase.requiredTools(), evalCase.forbiddenTools(), mustContain,
                mustContainAny, mustNotContain, evalCase.confirmation(), Map.of());
        return new ResolvedCase(resolved, null);
    }

    private static List<String> replace(List<String> values, String marker, String replacement) {
        return values.stream().map(value -> value.replace(marker, replacement)).toList();
    }

    private AgentEvalResult skipped(AgentEvalCase evalCase, String provider, String missingVariable) {
        AgentEvalObservation observation = new AgentEvalObservation(
                provider, null, null, "", List.of(), false,
                "Missing system property: " + missingVariable, 0L);
        return new AgentEvalResult(evalCase.id(), evalCase.category(), evalCase.riskLevel(),
                provider, null, "SKIPPED", List.of(), observation);
    }

    private static String property(String name, String defaultValue) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property: " + name);
        }
        return value.trim();
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());
    }

    private record ResolvedCase(AgentEvalCase evalCase, String missingVariable) {
    }
}
