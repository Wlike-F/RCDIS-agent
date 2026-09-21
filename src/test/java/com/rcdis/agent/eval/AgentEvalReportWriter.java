package com.rcdis.agent.eval;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Writes machine-readable evidence and a human-readable provider comparison. */
public final class AgentEvalReportWriter {

    private AgentEvalReportWriter() {
        throw new UnsupportedOperationException("AgentEvalReportWriter cannot be instantiated");
    }

    public static void write(ObjectMapper objectMapper, Path outputDirectory, List<AgentEvalResult> results) {
        try {
            Files.createDirectories(outputDirectory);
            Files.writeString(outputDirectory.resolve("agent-eval-results.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(results),
                    StandardCharsets.UTF_8);
            Files.writeString(outputDirectory.resolve("agent-eval-report.md"), markdown(results),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write Agent evaluation reports", exception);
        }
    }

    private static String markdown(List<AgentEvalResult> results) {
        StringBuilder report = new StringBuilder();
        report.append("# Agent Eval Report\n\n");
        report.append("Generated at: ").append(OffsetDateTime.now()).append("\n\n");
        report.append("## Provider comparison\n\n");
        report.append("| Provider | Model | Executed | Passed | Pass rate | Avg latency |\n");
        report.append("|---|---|---:|---:|---:|---:|\n");
        for (ProviderSummary summary : summarize(results)) {
            report.append("|").append(escape(summary.providerId())).append('|')
                    .append(escape(summary.modelName())).append('|')
                    .append(summary.executed()).append('|')
                    .append(summary.passed()).append('|')
                    .append(summary.passRate()).append("%|")
                    .append(summary.averageLatencyMs()).append(" ms|\n");
        }
        report.append("\n## Failed cases\n\n");
        boolean hasFailure = false;
        for (AgentEvalResult result : results) {
            if (!"FAILED".equals(result.status())) {
                continue;
            }
            hasFailure = true;
            report.append("### ").append(result.providerId()).append(" / ").append(result.caseId()).append("\n\n");
            report.append("- Category: ").append(result.category()).append("\n");
            report.append("- Risk: ").append(result.riskLevel()).append("\n");
            report.append("- Tools: ").append(result.observation().tools()).append("\n");
            report.append("- Confirmation: ").append(result.observation().confirmationRequested()).append("\n");
            for (String failure : result.failures()) {
                report.append("- Failure: ").append(failure).append("\n");
            }
            report.append('\n');
        }
        if (!hasFailure) {
            report.append("No failed cases.\n");
        }
        return report.toString();
    }

    private static List<ProviderSummary> summarize(List<AgentEvalResult> results) {
        Map<String, List<AgentEvalResult>> grouped = new LinkedHashMap<>();
        for (AgentEvalResult result : results) {
            if (!"SKIPPED".equals(result.status())) {
                grouped.computeIfAbsent(result.providerId(), ignored -> new ArrayList<>()).add(result);
            }
        }
        List<ProviderSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<AgentEvalResult>> entry : grouped.entrySet()) {
            List<AgentEvalResult> providerResults = entry.getValue();
            long passed = providerResults.stream().filter(result -> "PASSED".equals(result.status())).count();
            long totalLatency = providerResults.stream()
                    .mapToLong(result -> result.observation().durationMs())
                    .sum();
            String modelName = providerResults.stream()
                    .map(AgentEvalResult::modelName)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse("unknown");
            BigDecimal rate = BigDecimal.valueOf(passed * 100L)
                    .divide(BigDecimal.valueOf(providerResults.size()), 2, RoundingMode.HALF_UP);
            summaries.add(new ProviderSummary(entry.getKey(), modelName, providerResults.size(), passed,
                    rate, totalLatency / providerResults.size()));
        }
        summaries.sort(Comparator.comparing(ProviderSummary::providerId));
        return summaries;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private record ProviderSummary(
            String providerId,
            String modelName,
            int executed,
            long passed,
            BigDecimal passRate,
            long averageLatencyMs
    ) {
    }
}
