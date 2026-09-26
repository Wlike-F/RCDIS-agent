package com.rcdis.agent.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.rcdis.agent.vo.AgentMemoryVO.SummaryFactVO;

/**
 * Bounds {@code chat_session.summary_facts}, which would otherwise grow monotonically.
 *
 * <p>{@code ContextCompressor.mergeFacts} is a pure union-dedup: every compression adds facts and
 * nothing is ever dropped, and the whole array is rendered into the prompt each turn. Left unbounded
 * the "compression" mechanism becomes the main source of context growth, and because the facts block
 * sits at the head of the session-stable zone, every change to it also invalidates the provider's
 * prompt-cache prefix behind it.</p>
 *
 * <p>Eviction is two-staged and fully deterministic (no LLM, no clock, no randomness) so the same
 * input always yields byte-identical output:</p>
 * <ol>
 *   <li><b>Per-type cap</b> — within each fact type keep the newest {@code maxPerType} by
 *       {@code turnSeq}. This stops one chatty type (typically {@code tool_result}) from crowding out
 *       everything else.</li>
 *   <li><b>Total cap</b> — if still over budget, drop the lowest-priority type first and the oldest
 *       {@code turnSeq} within a priority. Unresolved todos and decisions therefore outlive
 *       re-queryable tool conclusions, even when the tool conclusion is newer.</li>
 * </ol>
 *
 * <p>Survivors are always re-emitted in their incoming order rather than in ranking order, so the
 * rendered prompt block stays stable across compressions that evict nothing.</p>
 */
final class SummaryFactQuota {

    /**
     * Keep-priority by fact type; a higher value survives longer when the total cap bites.
     * {@code tool_result} ranks lowest because a tool conclusion can always be re-queried, whereas a
     * decision or an unresolved todo exists nowhere else once its raw turn leaves the window.
     */
    private static final Map<String, Integer> TYPE_PRIORITY = Map.of(
            "todo", 4,
            "decision", 4,
            "amount", 3,
            "order", 3,
            "project", 3,
            "user", 2,
            "tool_result", 1);

    /** Priority for a type the compressor prompt never defined (including a null type). */
    private static final int UNKNOWN_TYPE_PRIORITY = 0;

    private SummaryFactQuota() {
    }

    /**
     * Applies the per-type and total caps.
     *
     * @param facts      merged facts in incoming order (may be null)
     * @param maxPerType maximum survivors per fact type; {@code <= 0} disables the per-type cap
     * @param maxTotal   maximum survivors overall; {@code <= 0} disables the total cap
     * @return a bounded list in incoming order; never null
     */
    static List<SummaryFactVO> apply(List<SummaryFactVO> facts, int maxPerType, int maxTotal) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        List<SummaryFactVO> bounded = facts;
        if (maxPerType > 0) {
            bounded = retain(bounded, perTypeSurvivors(bounded, maxPerType));
        }
        if (maxTotal > 0 && bounded.size() > maxTotal) {
            bounded = retain(bounded, totalCapSurvivors(bounded, maxTotal));
        }
        return bounded;
    }

    // ---------- staging ----------

    private static Set<SummaryFactVO> perTypeSurvivors(List<SummaryFactVO> facts, int maxPerType) {
        Map<String, List<SummaryFactVO>> byType = new LinkedHashMap<>();
        for (SummaryFactVO fact : facts) {
            byType.computeIfAbsent(typeKey(fact), key -> new ArrayList<>()).add(fact);
        }
        Set<SummaryFactVO> survivors = newIdentitySet();
        for (List<SummaryFactVO> group : byType.values()) {
            if (group.size() <= maxPerType) {
                survivors.addAll(group);
                continue;
            }
            // Newest first; the sort is stable so equal turnSeq keeps the incoming order.
            List<SummaryFactVO> newestFirst = new ArrayList<>(group);
            newestFirst.sort(Comparator.comparingInt(SummaryFactQuota::turnSeqOrOldest).reversed());
            survivors.addAll(newestFirst.subList(0, maxPerType));
        }
        return survivors;
    }

    private static Set<SummaryFactVO> totalCapSurvivors(List<SummaryFactVO> facts, int maxTotal) {
        List<SummaryFactVO> ranked = new ArrayList<>(facts);
        // Highest priority first, newest first within a priority; stable on a full tie.
        ranked.sort(Comparator
                .comparingInt(SummaryFactQuota::priorityOf)
                .thenComparingInt(SummaryFactQuota::turnSeqOrOldest)
                .reversed());
        Set<SummaryFactVO> survivors = newIdentitySet();
        survivors.addAll(ranked.subList(0, maxTotal));
        return survivors;
    }

    /** Re-emits the survivors in incoming order so the prompt block bytes stay stable. */
    private static List<SummaryFactVO> retain(List<SummaryFactVO> facts, Set<SummaryFactVO> survivors) {
        List<SummaryFactVO> retained = new ArrayList<>(survivors.size());
        for (SummaryFactVO fact : facts) {
            if (survivors.contains(fact)) {
                retained.add(fact);
            }
        }
        return retained;
    }

    // ---------- helpers ----------

    private static String typeKey(SummaryFactVO fact) {
        return fact.type() == null ? "" : fact.type();
    }

    private static int priorityOf(SummaryFactVO fact) {
        return TYPE_PRIORITY.getOrDefault(typeKey(fact), UNKNOWN_TYPE_PRIORITY);
    }

    /** A missing turnSeq cannot be attributed to a turn, so it is treated as the oldest. */
    private static int turnSeqOrOldest(SummaryFactVO fact) {
        return fact.turnSeq() == null ? Integer.MIN_VALUE : fact.turnSeq();
    }

    /**
     * Identity-based set: {@code SummaryFactVO} is a record, so equal-valued duplicates extracted
     * from different turns would otherwise collapse and evict more than the cap requires.
     */
    private static Set<SummaryFactVO> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
