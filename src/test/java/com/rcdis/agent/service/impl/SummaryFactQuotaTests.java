package com.rcdis.agent.service.impl;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.rcdis.agent.vo.AgentMemoryVO.SummaryFactVO;

/**
 * Unit tests for {@link SummaryFactQuota}, which bounds the otherwise monotonically growing
 * {@code chat_session.summary_facts} array. Pure functions, no DB.
 *
 * <p>Two invariants matter beyond "it stays small": eviction must be deterministic (same input ->
 * same output, so the Zone-B prompt bytes are stable for provider caching) and survivors must keep
 * their incoming order (facts are rendered oldest-turn-first into the prompt).</p>
 */
class SummaryFactQuotaTests {

    @Test
    void perTypeCapEvictsTheOldestFactsOfThatType() {
        List<SummaryFactVO> facts = List.of(
                fact("amount", "金额 1,200.00 元", 1),
                fact("amount", "金额 300.00 元", 2),
                fact("amount", "金额 45.50 元", 3),
                fact("amount", "金额 9,999.00 元", 4),
                fact("amount", "金额 12.00 元", 5));

        List<SummaryFactVO> bounded = SummaryFactQuota.apply(facts, 2, 100);

        assertThat(bounded).extracting(SummaryFactVO::turnSeq).containsExactly(4, 5);
    }

    @Test
    void totalCapEvictsTheLowestPriorityTypesFirstEvenWhenTheyAreNewest() {
        List<SummaryFactVO> facts = List.of(
                fact("todo", "待办：补齐发票号", 1),
                fact("amount", "金额 1,200.00 元", 5),
                fact("tool_result", "query_project_budget -> 剩余 3,000.00", 9));

        List<SummaryFactVO> bounded = SummaryFactQuota.apply(facts, 10, 2);

        // tool_result is re-queryable, so it loses its slot to the older but higher-value facts.
        assertThat(bounded).extracting(SummaryFactVO::type).containsExactly("todo", "amount");
    }

    @Test
    void unknownAndNullTypesAreEvictedBeforeKnownTypes() {
        List<SummaryFactVO> facts = List.of(
                fact("tool_result", "工具结论", 1),
                fact("something_the_model_invented", "未知类型", 2),
                fact(null, "无类型", 3),
                fact("decision", "决定：走 P-0001", 4));

        List<SummaryFactVO> bounded = SummaryFactQuota.apply(facts, 10, 2);

        // The two invented/null-typed facts are dropped; survivors stay in incoming order.
        assertThat(bounded).extracting(SummaryFactVO::type).containsExactly("tool_result", "decision");
    }

    @Test
    void nullTurnSeqIsTreatedAsOldestWithinTheSameType() {
        List<SummaryFactVO> facts = List.of(
                fact("amount", "无序号金额", null),
                fact("amount", "金额 1.00 元", 7),
                fact("amount", "金额 2.00 元", 8));

        List<SummaryFactVO> bounded = SummaryFactQuota.apply(facts, 2, 100);

        assertThat(bounded).extracting(SummaryFactVO::text)
                .containsExactly("金额 1.00 元", "金额 2.00 元");
    }

    @Test
    void nonPositiveLimitsMeanUnbounded() {
        List<SummaryFactVO> facts = new ArrayList<>();
        for (int seq = 1; seq <= 30; seq++) {
            facts.add(fact("amount", "金额 " + seq, seq));
        }

        assertThat(SummaryFactQuota.apply(facts, 0, 0)).hasSize(30);
        assertThat(SummaryFactQuota.apply(facts, -1, -5)).hasSize(30);
    }

    @Test
    void perTypeCapIsAppliedBeforeTheTotalCap() {
        // 6 amount + 1 todo; the per-type cap trims amount to 2 first, then the total cap of 3 fits.
        List<SummaryFactVO> facts = List.of(
                fact("amount", "a1", 1),
                fact("amount", "a2", 2),
                fact("amount", "a3", 3),
                fact("amount", "a4", 4),
                fact("amount", "a5", 5),
                fact("amount", "a6", 6),
                fact("todo", "t1", 7));

        List<SummaryFactVO> bounded = SummaryFactQuota.apply(facts, 2, 3);

        assertThat(bounded).extracting(SummaryFactVO::text).containsExactly("a5", "a6", "t1");
    }

    @Test
    void survivorsKeepTheirIncomingOrderSoPromptBytesStayStable() {
        List<SummaryFactVO> facts = List.of(
                fact("project", "P-0001", 3),
                fact("todo", "补发票", 1),
                fact("amount", "1,200.00", 2));

        List<SummaryFactVO> bounded = SummaryFactQuota.apply(facts, 10, 10);

        assertThat(bounded).extracting(SummaryFactVO::type)
                .containsExactly("project", "todo", "amount");
    }

    @Test
    void emptyAndNullInputYieldEmptyResult() {
        assertThat(SummaryFactQuota.apply(List.of(), 5, 5)).isEmpty();
        assertThat(SummaryFactQuota.apply(null, 5, 5)).isEmpty();
    }

    private static SummaryFactVO fact(String type, String text, Integer turnSeq) {
        return new SummaryFactVO(type, text, turnSeq, null);
    }
}
