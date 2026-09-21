package com.rcdis.agent.eval;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Writes the version-anchored run manifest and the human-in-the-loop tuning report.
 *
 * <p>The report is generated deterministically from failure clusters; it recommends edits to the
 * system prompt but never modifies it. High-risk regressions against the baseline are flagged as
 * promotion blockers.</p>
 */
public final class AgentEvalTuningReportWriter {

    private static final int SECTION_TEXT_LIMIT = 400;
    private static final Set<String> BLOCKING_RISK_LEVELS = Set.of("HIGH", "CRITICAL");

    private AgentEvalTuningReportWriter() {
        throw new UnsupportedOperationException("AgentEvalTuningReportWriter cannot be instantiated");
    }

    public static void write(ObjectMapper objectMapper, Path outputDirectory,
            AgentEvalRunManifest current, AgentEvalRunManifest baseline,
            AgentEvalPromptAnchor prompt, AgentEvalTuningMap tuningMap, List<AgentEvalResult> results) {
        try {
            Files.createDirectories(outputDirectory);
            Files.writeString(outputDirectory.resolve("agent-eval-run.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(current),
                    StandardCharsets.UTF_8);
            Files.writeString(outputDirectory.resolve("agent-eval-tuning.md"),
                    markdown(current, baseline, prompt, tuningMap, results),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write Agent eval tuning artifacts", exception);
        }
    }

    private static String markdown(AgentEvalRunManifest current, AgentEvalRunManifest baseline,
            AgentEvalPromptAnchor prompt, AgentEvalTuningMap tuningMap, List<AgentEvalResult> results) {
        StringBuilder report = new StringBuilder();
        report.append("# Agent Eval 调优建议报告\n\n");
        report.append("- Run: ").append(current.runId()).append('\n');
        report.append("- Commit: ").append(current.gitCommit()).append('\n');
        report.append("- 提示词版本: ").append(current.promptSha256())
                .append("（").append(current.promptResource()).append("）\n");
        if (baseline != null) {
            report.append("- 基线 Run: ").append(baseline.runId()).append('\n');
            report.append("- 基线提示词版本: ").append(baseline.promptSha256()).append('\n');
            report.append("- 通过率: ").append(percent(current.passRate()))
                    .append("，基线: ").append(percent(baseline.passRate()))
                    .append("，Δ: ").append(deltaPercentagePoints(current.passRate(), baseline.passRate()))
                    .append(" 个百分点\n");
        } else {
            report.append("- 通过率: ").append(percent(current.passRate())).append("（本次未提供基线对比）\n");
        }
        appendCategoryTable(report, current, baseline);
        appendBlockingRegressions(report, baseline, results);
        appendClusters(report, tuningMap, prompt, results);
        return report.toString();
    }

    private static void appendCategoryTable(StringBuilder report, AgentEvalRunManifest current,
            AgentEvalRunManifest baseline) {
        Map<String, long[]> currentByCategory = groupByCategory(current.cases());
        report.append("\n## 分类错误率\n\n");
        if (baseline == null) {
            report.append("| Category | 执行 | 失败 | 错误率 |\n|---|---:|---:|---:|\n");
            for (Map.Entry<String, long[]> entry : currentByCategory.entrySet()) {
                report.append('|').append(entry.getKey()).append('|').append(entry.getValue()[0]).append('|')
                        .append(entry.getValue()[1]).append('|')
                        .append(percent(rate(entry.getValue()[1], entry.getValue()[0]))).append("|\n");
            }
            return;
        }
        Map<String, long[]> baselineByCategory = groupByCategory(baseline.cases());
        report.append("| Category | 执行 | 失败 | 错误率 | 基线错误率 | Δ |\n|---|---:|---:|---:|---:|---:|\n");
        Set<String> categories = new LinkedHashSet<>();
        categories.addAll(currentByCategory.keySet());
        categories.addAll(baselineByCategory.keySet());
        for (String category : categories) {
            long[] now = currentByCategory.getOrDefault(category, new long[2]);
            long[] before = baselineByCategory.getOrDefault(category, new long[2]);
            BigDecimal nowRate = rate(now[1], now[0]);
            BigDecimal beforeRate = rate(before[1], before[0]);
            report.append('|').append(category).append('|').append(now[0]).append('|').append(now[1]).append('|')
                    .append(percent(nowRate)).append('|').append(percent(beforeRate)).append('|')
                    .append(deltaPercentagePoints(nowRate, beforeRate)).append("|\n");
        }
    }

    private static Map<String, long[]> groupByCategory(List<AgentEvalRunManifest.CaseRecord> cases) {
        Map<String, long[]> byCategory = new LinkedHashMap<>();
        for (AgentEvalRunManifest.CaseRecord record : cases) {
            if ("SKIPPED".equals(record.status())) {
                continue;
            }
            long[] counts = byCategory.computeIfAbsent(record.category(), ignored -> new long[2]);
            counts[0]++;
            if ("FAILED".equals(record.status())) {
                counts[1]++;
            }
        }
        return byCategory;
    }

    private static void appendBlockingRegressions(StringBuilder report, AgentEvalRunManifest baseline,
            List<AgentEvalResult> results) {
        if (baseline == null) {
            return;
        }
        Set<String> baselinePassed = new HashSet<>();
        for (AgentEvalRunManifest.CaseRecord record : baseline.cases()) {
            if ("PASSED".equals(record.status())) {
                baselinePassed.add(record.caseId());
            }
        }
        List<AgentEvalResult> regressions = results.stream()
                .filter(result -> "FAILED".equals(result.status()) && baselinePassed.contains(result.caseId()))
                .toList();
        if (regressions.isEmpty()) {
            return;
        }
        boolean hasBlocking = regressions.stream().anyMatch(r -> BLOCKING_RISK_LEVELS.contains(r.riskLevel()));
        report.append("\n## 高风险回归（阻断晋级）\n\n");
        if (hasBlocking) {
            report.append("存在 HIGH/CRITICAL 用例相对基线由通过转为失败，本次提示词改动不得晋级，请回退或修复：\n\n");
        } else {
            report.append("无 HIGH/CRITICAL 回归；以下低风险用例发生回退，供参考。\n\n");
        }
        for (AgentEvalResult regression : regressions) {
            report.append("- ")
                    .append(BLOCKING_RISK_LEVELS.contains(regression.riskLevel()) ? "🚩 " : "")
                    .append(regression.caseId()).append("（").append(regression.riskLevel()).append("）：")
                    .append(String.join("；", regression.failures())).append('\n');
        }
    }

    private record Cluster(AgentEvalTuningMap.TuningEntry entry, Set<String> caseIds,
            Set<String> tools, Set<String> messages, int failureCount) {
    }

    private static void appendClusters(StringBuilder report, AgentEvalTuningMap tuningMap,
            AgentEvalPromptAnchor prompt, List<AgentEvalResult> results) {
        Map<AgentEvalTuningMap.TuningEntry, Cluster> clusters = new LinkedHashMap<>();
        for (AgentEvalResult result : results) {
            if (!"FAILED".equals(result.status())) {
                continue;
            }
            for (AgentEvalFailureClassifier.ClassifiedFailure failure :
                    AgentEvalFailureClassifier.classify(result.failures())) {
                AgentEvalTuningMap.TuningEntry entry = tuningMap.find(failure.type(), result.category())
                        .orElseGet(() -> new AgentEvalTuningMap.TuningEntry(failure.type().name(), List.of(),
                                List.of(), "未在 tuning-map.json 中找到映射，请人工分析失败原文。"));
                Cluster existing = clusters.get(entry);
                Set<String> caseIds = existing == null ? new LinkedHashSet<>() : existing.caseIds();
                Set<String> tools = existing == null ? new LinkedHashSet<>() : existing.tools();
                Set<String> messages = existing == null ? new LinkedHashSet<>() : existing.messages();
                caseIds.add(result.caseId());
                if (failure.tool() != null) {
                    tools.add(failure.tool());
                }
                messages.add(failure.message());
                clusters.put(entry, new Cluster(entry, caseIds, tools, messages,
                        (existing == null ? 0 : existing.failureCount()) + 1));
            }
        }
        if (clusters.isEmpty()) {
            report.append("\n## 结论\n\n本轮无失败用例，无需调优。\n");
            return;
        }
        report.append("\n## 失败簇 → 系统提示词调优建议\n\n");
        List<Cluster> ordered = new ArrayList<>(clusters.values());
        ordered.sort(Comparator.comparingInt(Cluster::failureCount).reversed());
        for (Cluster cluster : ordered) {
            appendCluster(report, prompt, cluster);
        }
    }

    private static void appendCluster(StringBuilder report, AgentEvalPromptAnchor prompt, Cluster cluster) {
        AgentEvalFailureType type = AgentEvalFailureType.valueOf(cluster.entry().failureType());
        report.append("### ").append(type.label()).append("（")
                .append(cluster.failureCount()).append(" 处失败 / ")
                .append(cluster.caseIds().size()).append(" 个用例）\n\n");
        report.append("- 命中用例：").append(String.join("、", cluster.caseIds())).append('\n');
        report.append("- 涉及工具：")
                .append(cluster.tools().isEmpty() ? "-" : String.join("、", cluster.tools())).append('\n');
        report.append("- 失败原文：\n");
        for (String message : cluster.messages()) {
            report.append("  - ").append(message).append('\n');
        }
        report.append("- 建议改法：").append(cluster.entry().hint()).append('\n');
        for (String tunableId : cluster.entry().tunableIds()) {
            AgentEvalPromptAnchor.PromptSection section = prompt.sections().get(tunableId);
            if (section == null) {
                continue;
            }
            report.append("- 建议检查小节：「").append(section.title()).append("」（@tunable:")
                    .append(tunableId).append("）\n");
            report.append("  > ").append(truncate(section.text()).replace("\n", "\n  > ")).append('\n');
        }
        report.append('\n');
    }

    private static String truncate(String text) {
        String normalized = text.strip();
        return normalized.length() <= SECTION_TEXT_LIMIT
                ? normalized
                : normalized.substring(0, SECTION_TEXT_LIMIT) + "…（已截断）";
    }

    private static BigDecimal rate(long failed, long executed) {
        return executed == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(failed).divide(BigDecimal.valueOf(executed), 4, RoundingMode.HALF_UP);
    }

    private static String percent(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "%";
    }

    private static String deltaPercentagePoints(BigDecimal current, BigDecimal baseline) {
        BigDecimal diff = current.subtract(baseline).multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
        return (diff.signum() > 0 ? "+" : "") + diff.toPlainString();
    }
}
