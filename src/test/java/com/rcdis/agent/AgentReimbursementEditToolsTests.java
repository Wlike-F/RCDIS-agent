package com.rcdis.agent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.agent.AgentToolContext;
import com.rcdis.agent.agent.tools.ReimbursementTools;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.dto.ProjectCreateRequest;
import com.rcdis.agent.dto.ReimbursementActionRequest;
import com.rcdis.agent.dto.ReimbursementCreateRequest;
import com.rcdis.agent.dto.ReimbursementItemInput;
import com.rcdis.agent.service.AgentPendingActionService;
import com.rcdis.agent.service.ReimbursementService;
import com.rcdis.agent.service.ResearchProjectService;
import com.rcdis.agent.vo.ProjectVO;
import com.rcdis.agent.vo.ReimbursementDetailVO;

/**
 * End-to-end coverage for the edit/void write tools: tool proposal → user confirmation → deterministic
 * execution, including the relaxed rule that a rejected order can be edited before resubmission.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop"
})
class AgentReimbursementEditToolsTests {

    @Autowired
    private ReimbursementTools reimbursementTools;

    @Autowired
    private ReimbursementService reimbursementService;

    @Autowired
    private ResearchProjectService researchProjectService;

    @Autowired
    private AgentPendingActionService pendingActionService;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void clearUser() {
        CurrentUserContextHolder.clear();
    }

    @Test
    void updateToolProposalReplacesDraftLinesAfterConfirmation() throws Exception {
        CurrentUserTO researcher = user("edit-u1", "edit-draft-user", "RESEARCHER");
        CurrentUserContextHolder.set(researcher);
        Long id = createDraft(researcher, new BigDecimal("100.00"), "INV-ED-1");

        String proposal = reimbursementTools.updateReimbursement(id, List.of(
                item(new BigDecimal("180.00"), "INV-ED-1"),
                item(new BigDecimal("20.00"), "INV-ED-2")), "金额修正", toolContext(researcher, "conv-edit-1"));

        assertThat(proposal).contains("\"pending\":true");
        var confirmed = pendingActionService.resolveConfirmation(
                "conv-edit-1", confirmationId(proposal), true);
        assertThat(confirmed.executed()).isTrue();

        ReimbursementDetailVO after = reimbursementService.getReimbursement(id);
        assertThat(after.items()).hasSize(2);
        assertThat(after.order().totalAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    void rejectedOrderIsEditableAndApprovedOrderIsNot() throws Exception {
        CurrentUserTO researcher = user("edit-u2", "edit-reject-user", "RESEARCHER");
        CurrentUserTO approver = user("edit-a2", "edit-approver", "APPROVER");
        CurrentUserContextHolder.set(researcher);
        Long rejectedId = submittedOrder(researcher, "INV-ED-R1");

        CurrentUserContextHolder.set(approver);
        reimbursementService.rejectReimbursement(rejectedId, new ReimbursementActionRequest("金额与发票不符"));

        CurrentUserContextHolder.set(researcher);
        String proposal = reimbursementTools.updateReimbursement(rejectedId, List.of(
                item(new BigDecimal("120.00"), "INV-ED-R1")), "按驳回意见修正", toolContext(researcher, "conv-edit-2"));
        assertThat(pendingActionService.resolveConfirmation(
                "conv-edit-2", confirmationId(proposal), true).executed()).isTrue();
        assertThat(reimbursementService.getReimbursement(rejectedId).order().totalAmount())
                .isEqualByComparingTo(new BigDecimal("120.00"));

        // An approved order must refuse the same edit, and must not create a proposal.
        Long approvedId = submittedOrder(researcher, "INV-ED-A1");
        CurrentUserContextHolder.set(approver);
        reimbursementService.approveReimbursement(approvedId, new ReimbursementActionRequest("同意"));

        CurrentUserContextHolder.set(researcher);
        String blocked = reimbursementTools.updateReimbursement(approvedId, List.of(
                item(new BigDecimal("10.00"), "INV-ED-A1")), "尝试改已通过的单", toolContext(researcher, "conv-edit-3"));
        assertThat(blocked).contains("\"ok\":false").contains("仅草稿");
    }

    @Test
    void voidToolProposalMarksOrderVoidAndRequiresReason() {
        CurrentUserTO researcher = user("edit-u3", "edit-void-user", "RESEARCHER");
        CurrentUserContextHolder.set(researcher);
        Long id = createDraft(researcher, new BigDecimal("70.00"), "INV-ED-V1");

        String missingReason = reimbursementTools.voidReimbursement(id, "  ", toolContext(researcher, "conv-void-1"));
        assertThat(missingReason).contains("\"ok\":false").contains("reason");

        String proposal = reimbursementTools.voidReimbursement(id, "录错了项目", toolContext(researcher, "conv-void-1"));
        assertThat(pendingActionService.resolveConfirmation(
                "conv-void-1", confirmationId(proposal), true).executed()).isTrue();
        assertThat(reimbursementService.getReimbursement(id).order().status()).isEqualTo("void");
    }

    // ---------- helpers ----------

    private Long createDraft(CurrentUserTO owner, BigDecimal amount, String invoiceNo) {
        ProjectVO project = researchProjectService.createProject(new ProjectCreateRequest(
                "EDIT-" + UUID.randomUUID(), "Edit Tools Project", owner.username(), "TEST",
                new BigDecimal("50000.00"), LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                "ACTIVE", true));
        ReimbursementDetailVO created = reimbursementService.createReimbursement(
                new ReimbursementCreateRequest(project.id(), owner.username(), "reimbursement",
                        List.of(item(amount, invoiceNo)), "tool test draft", false));
        return created.order().id();
    }

    private Long submittedOrder(CurrentUserTO owner, String invoiceNo) {
        Long id = createDraft(owner, new BigDecimal("100.00"), invoiceNo);
        reimbursementService.submitReimbursement(id, new ReimbursementActionRequest("提交测试"));
        return id;
    }

    private static ReimbursementItemInput item(BigDecimal amount, String invoiceNo) {
        return new ReimbursementItemInput(
                amount, LocalDate.of(2026, 9, 10), "Vendor-" + invoiceNo, invoiceNo,
                null, "edit tools line", null);
    }

    private static ToolContext toolContext(CurrentUserTO user, String conversationId) {
        return new ToolContext(AgentToolContext.asToolContextMap(
                new AgentToolContext(null, conversationId, user, null, null)));
    }

    private String confirmationId(String proposalJson) {
        try {
            return objectMapper.readTree(proposalJson).path("confirmationId").asText();
        } catch (Exception exception) {
            throw new IllegalStateException("提案返回不是合法 JSON：" + proposalJson, exception);
        }
    }

    private static CurrentUserTO user(String id, String username, String role) {
        return CurrentUserTO.of(id, username, "test", null, Set.of(role));
    }
}
