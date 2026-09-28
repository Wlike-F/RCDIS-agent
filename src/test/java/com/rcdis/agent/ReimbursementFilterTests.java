package com.rcdis.agent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.dto.ProjectCreateRequest;
import com.rcdis.agent.dto.ReimbursementCreateRequest;
import com.rcdis.agent.dto.ReimbursementItemInput;
import com.rcdis.agent.dto.ReimbursementPageRequest;
import com.rcdis.agent.service.ReimbursementService;
import com.rcdis.agent.service.ResearchProjectService;
import com.rcdis.agent.vo.ProjectVO;
import com.rcdis.agent.vo.ReimbursementVO;

/**
 * List filters added for the reimbursement workbench: applicant, payment type and an amount range,
 * plus case-insensitive status values (the UI sends lowercase, older callers send uppercase).
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop"
})
class ReimbursementFilterTests {

    @Autowired
    private ReimbursementService reimbursementService;

    @Autowired
    private ResearchProjectService researchProjectService;

    @AfterEach
    void clearUser() {
        CurrentUserContextHolder.clear();
    }

    @Test
    void filtersByApplicantPaymentTypeAndAmountRange() {
        CurrentUserTO admin = CurrentUserTO.of("rf-admin", "rf-admin", "test", null, Set.of("ADMIN"));
        CurrentUserContextHolder.set(admin);
        ProjectVO project = researchProjectService.createProject(new ProjectCreateRequest(
                "RF-" + UUID.randomUUID(), "筛选测试项目", "rf-admin", "TEST",
                new BigDecimal("100000.00"), LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                "ACTIVE", true));

        order(project, "rf-alice", "reimbursement", new BigDecimal("100.00"));
        order(project, "rf-alice", "public_payment", new BigDecimal("500.00"));
        order(project, "rf-bob", "reimbursement", new BigDecimal("900.00"));

        // Scope every assertion to this test's project: the H2 database is shared across test
        // classes in the same Spring context.
        Long pid = project.id();
        assertThat(page(admin, new ReimbursementPageRequest(
                1, 50, pid, null, null, "rf-alice", null, null, null)).records())
                .hasSize(2);

        assertThat(page(admin, new ReimbursementPageRequest(
                1, 50, pid, null, null, null, "public_payment", null, null)).records())
                .singleElement()
                .satisfies(vo -> assertThat(vo.applicant()).isEqualTo("rf-alice"));

        assertThat(page(admin, new ReimbursementPageRequest(
                1, 50, pid, null, null, null, null, new BigDecimal("400.00"), new BigDecimal("600.00")))
                .records())
                .singleElement()
                .satisfies(vo -> assertThat(vo.totalAmount()).isEqualByComparingTo(new BigDecimal("500.00")));

        // Combined filters intersect.
        assertThat(page(admin, new ReimbursementPageRequest(
                1, 50, pid, null, null, "rf-bob", "reimbursement", new BigDecimal("800.00"), null)).records())
                .hasSize(1);
        assertThat(page(admin, new ReimbursementPageRequest(
                1, 50, pid, null, null, "rf-bob", "public_payment", null, null)).records())
                .isEmpty();
    }

    @Test
    void statusFilterAcceptsAnyCase() {
        CurrentUserTO admin = CurrentUserTO.of("rf-admin2", "rf-admin2", "test", null, Set.of("ADMIN"));
        CurrentUserContextHolder.set(admin);
        ProjectVO project = researchProjectService.createProject(new ProjectCreateRequest(
                "RF2-" + UUID.randomUUID(), "状态大小写项目", "rf-admin2", "TEST",
                new BigDecimal("100000.00"), LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                "ACTIVE", true));
        order(project, "rf-admin2", "reimbursement", new BigDecimal("120.00"));

        List<ReimbursementVO> lower = page(admin, new ReimbursementPageRequest(
                1, 50, project.id(), "draft", null, null, null, null, null)).records();
        List<ReimbursementVO> upper = page(admin, new ReimbursementPageRequest(
                1, 50, project.id(), "DRAFT", null, null, null, null, null)).records();
        assertThat(lower).hasSize(1);
        assertThat(upper).hasSize(1);
        assertThat(lower.get(0).reimbursementNo()).isEqualTo(upper.get(0).reimbursementNo());
    }

    // ---------- helpers ----------

    private void order(ProjectVO project, String applicant, String paymentType, BigDecimal amount) {
        CurrentUserTO previous = CurrentUserContextHolder.currentOrAnonymous();
        CurrentUserContextHolder.set(CurrentUserTO.of(
                applicant, applicant, "test", null, Set.of("RESEARCHER")));
        reimbursementService.createReimbursement(new ReimbursementCreateRequest(
                project.id(), applicant, paymentType,
                List.of(new ReimbursementItemInput(
                        amount, LocalDate.of(2026, 9, 20), "Vendor", "INV-RF-" + UUID.randomUUID(),
                        null, "筛选测试明细", null)),
                "filter test", false));
        CurrentUserContextHolder.set(previous);
    }

    private com.rcdis.agent.common.response.PageResponse<ReimbursementVO> page(
            CurrentUserTO caller, ReimbursementPageRequest request) {
        CurrentUserContextHolder.set(caller);
        return reimbursementService.pageReimbursements(request);
    }
}
