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
import com.rcdis.agent.service.OverviewService;
import com.rcdis.agent.service.ResearchProjectService;
import com.rcdis.agent.vo.OverviewSummaryVO;
import com.rcdis.agent.vo.ProjectVO;

/**
 * Role-aware overview aggregation: researchers see only their own reimbursement stats and the
 * budget headroom of projects they are responsible for; admins see the global block.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop"
})
class OverviewSummaryTests {

    @Autowired
    private OverviewService overviewService;

    @Autowired
    private ResearchProjectService researchProjectService;

    @Autowired
    private com.rcdis.agent.service.ReimbursementService reimbursementService;

    @AfterEach
    void clearUser() {
        CurrentUserContextHolder.clear();
    }

    @Test
    void memberOverviewAggregatesOwnOrdersAndProjects() {
        CurrentUserTO researcher = CurrentUserTO.of(
                "ov-u1", "ov-researcher", "test", null, Set.of("RESEARCHER"));
        CurrentUserContextHolder.set(researcher);

        String code = "OV-" + UUID.randomUUID();
        ProjectVO created = researchProjectService.createProject(new ProjectCreateRequest(
                code, "Overview 项目", "ov-researcher", "TEST", new BigDecimal("5000.00"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "ACTIVE", true));
        reimbursementService.createReimbursement(new ReimbursementCreateRequest(
                created.id(), "ov-researcher", "reimbursement",
                List.of(new ReimbursementItemInput(
                        new BigDecimal("100.00"), LocalDate.of(2026, 9, 21), "V", "INV-OV-1",
                        null, "overview", null)),
                "overview test", false));

        OverviewSummaryVO overview = overviewService.summary(researcher);

        assertThat(overview.role()).isEqualTo("MEMBER");
        assertThat(overview.displayName()).isEqualTo("ov-researcher");
        assertThat(overview.myProjectCount()).isEqualTo(1);
        assertThat(overview.myProjectsTotalBudget()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(overview.myProjects()).singleElement().satisfies(myProject -> {
            assertThat(myProject.projectCode()).isEqualTo(code);
            assertThat(myProject.available()).isEqualByComparingTo(new BigDecimal("5000.00"));
        });
        assertThat(overview.myDraftCount()).isEqualTo(1);
        assertThat(overview.mySubmittedCount()).isZero();
        assertThat(overview.myApprovedCount()).isZero();
        assertThat(overview.mySubmittedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        // Global admin block must stay empty for a member.
        assertThat(overview.projectCount()).isNull();
    }

    @Test
    void adminOverviewReturnsGlobalBlockWithoutPersonalScope() {
        CurrentUserTO admin = CurrentUserTO.of(
                "ov-admin", "ov-admin", "test", null, Set.of("ADMIN"));

        OverviewSummaryVO overview = overviewService.summary(admin);

        assertThat(overview.role()).isEqualTo("ADMIN");
        assertThat(overview.projectCount()).isNotNull();
        assertThat(overview.totalBudget()).isNotNull();
        assertThat(overview.enabledProviders()).isNotNull();
        assertThat(overview.totalProviders()).isNotNull();
        assertThat(overview.pendingApprovalCount()).isNotNull();
        assertThat(overview.myProjects()).isEmpty();
    }
}
