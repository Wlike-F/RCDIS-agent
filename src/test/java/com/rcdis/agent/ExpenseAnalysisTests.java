package com.rcdis.agent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.dto.ProjectCreateRequest;
import com.rcdis.agent.dto.ReimbursementActionRequest;
import com.rcdis.agent.dto.ReimbursementCreateRequest;
import com.rcdis.agent.dto.ReimbursementItemInput;
import com.rcdis.agent.service.ExpenseAnalysisService;
import com.rcdis.agent.service.ResearchProjectService;
import com.rcdis.agent.vo.ExpenseSummaryVO;
import com.rcdis.agent.vo.ProjectVO;

/**
 * Deterministic expense analytics over reimbursement lines: SQL-side aggregation, status scoping,
 * month filtering and the whitelist handling of the group dimension.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop"
})
class ExpenseAnalysisTests {

    @Autowired
    private ExpenseAnalysisService expenseAnalysisService;

    @Autowired
    private ResearchProjectService researchProjectService;

    @Autowired
    private com.rcdis.agent.service.ReimbursementService reimbursementService;

    @AfterEach
    void clearUser() {
        CurrentUserContextHolder.clear();
    }

    @Test
    void summarizesApprovedSpendByProjectAndMonth() {
        CurrentUserTO owner = CurrentUserTO.of(
                "exp-u1", "exp-owner", "test", null, Set.of("RESEARCHER"));
        CurrentUserContextHolder.set(owner);
        String projectCode = "EXP-" + UUID.randomUUID();
        ProjectVO project = createProject(owner, projectCode);

        // 100 + 50 approved in 2026-08, 30 approved in 2026-09, 400 still draft (not spend).
        order(project, owner, new BigDecimal("100.00"), LocalDate.of(2026, 8, 5), "INV-E-1", true);
        order(project, owner, new BigDecimal("50.00"), LocalDate.of(2026, 8, 20), "INV-E-2", true);
        order(project, owner, new BigDecimal("30.00"), LocalDate.of(2026, 9, 3), "INV-E-3", true);
        order(project, owner, new BigDecimal("400.00"), LocalDate.of(2026, 9, 4), "INV-E-4", false);

        ExpenseSummaryVO total = expenseAnalysisService.summarize(
                new com.rcdis.agent.dto.ExpenseQueryFilter(projectCode, null, null, null, null, List.of()),
                null, 10);
        assertThat(total.grandTotal()).isEqualByComparingTo(new BigDecimal("180.00"));
        assertThat(total.totalCount()).isEqualTo(3);
        assertThat(total.statusScope()).containsExactly("approved");

        ExpenseSummaryVO byMonth = expenseAnalysisService.summarize(
                new com.rcdis.agent.dto.ExpenseQueryFilter(projectCode, null, null, null, null, List.of()),
                "month", 10);
        assertThat(byMonth.rows()).hasSize(2);
        assertThat(byMonth.rows().get(0).getGroupKey()).isEqualTo("2026-08");
        assertThat(byMonth.rows().get(0).getTotalAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(byMonth.rows().get(1).getGroupKey()).isEqualTo("2026-09");

        ExpenseSummaryVO august = expenseAnalysisService.summarize(
                new com.rcdis.agent.dto.ExpenseQueryFilter(
                        projectCode, null, "2026-08", null, null, List.of()),
                null, 10);
        assertThat(august.grandTotal()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(august.totalCount()).isEqualTo(2);

        // Drafts become spend only after approval; widening the status scope includes them.
        ExpenseSummaryVO withDrafts = expenseAnalysisService.summarize(
                new com.rcdis.agent.dto.ExpenseQueryFilter(
                        projectCode, null, null, null, null, List.of("approved", "draft")),
                "status", 10);
        assertThat(withDrafts.grandTotal()).isEqualByComparingTo(new BigDecimal("580.00"));
        assertThat(withDrafts.rows()).extracting(row -> row.getGroupKey())
                .containsExactlyInAnyOrder("approved", "draft");

        assertThat(expenseAnalysisService.listItems(
                new com.rcdis.agent.dto.ExpenseQueryFilter(
                        projectCode, null, null, null, null, List.of("draft")), 10))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.getAmount()).isEqualByComparingTo(new BigDecimal("400.00"));
                    assertThat(line.getProjectCode()).isEqualTo(projectCode);
                    assertThat(line.getOrderStatus()).isEqualTo("draft");
                });
    }

    @Test
    void unknownGroupDimensionFallsBackToSingleBucketAndBadMonthIsRejected() {
        CurrentUserTO owner = CurrentUserTO.of(
                "exp-u2", "exp-owner-2", "test", null, Set.of("RESEARCHER"));
        CurrentUserContextHolder.set(owner);

        ExpenseSummaryVO summary = expenseAnalysisService.summarize(
                new com.rcdis.agent.dto.ExpenseQueryFilter(null, null, null, null, null, List.of()),
                "project; drop table research_project", 10);
        assertThat(summary.groupBy()).isEqualTo("none");
        assertThat(summary.rows()).allSatisfy(row -> assertThat(row.getGroupKey()).isEqualTo("ALL"));

        assertThatThrownBy(() -> expenseAnalysisService.summarize(
                new com.rcdis.agent.dto.ExpenseQueryFilter(null, null, "2026/08", null, null, List.of()),
                null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM");
    }

    // ---------- helpers ----------

    private ProjectVO createProject(CurrentUserTO owner, String code) {
        return researchProjectService.createProject(new ProjectCreateRequest(
                code, "Expense Analysis Project", owner.username(), "TEST",
                new BigDecimal("100000.00"), LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                "ACTIVE", true));
    }

    private void order(ProjectVO project, CurrentUserTO owner, BigDecimal amount,
                       LocalDate expenseDate, String invoiceNo, boolean approve) {
        Long id = reimbursementService.createReimbursement(new ReimbursementCreateRequest(
                project.id(), owner.username(), "reimbursement",
                List.of(new ReimbursementItemInput(
                        amount, expenseDate, "Vendor-" + invoiceNo, invoiceNo,
                        null, "expense analysis line", null)),
                "expense analysis", false)).order().id();
        if (!approve) {
            return;
        }
        reimbursementService.submitReimbursement(id, new ReimbursementActionRequest("submit"));
        CurrentUserTO previous = CurrentUserContextHolder.currentOrAnonymous();
        CurrentUserContextHolder.set(CurrentUserTO.of(
                "exp-approver-" + invoiceNo, "exp-approver-" + invoiceNo, "test", null, Set.of("APPROVER")));
        reimbursementService.approveReimbursement(id, new ReimbursementActionRequest("同意"));
        CurrentUserContextHolder.set(previous);
    }
}
