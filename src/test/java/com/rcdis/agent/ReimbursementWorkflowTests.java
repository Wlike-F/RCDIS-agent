package com.rcdis.agent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * End-to-end workflow tests for the reimbursement center,
 * covering creation, material check gating, submit / approve / reject
 * transitions and the duplicate-link guard.
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "rcdis.feishu.enabled=false",
                "rcdis.feishu.client-type=noop"
        })
class ReimbursementWorkflowTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void reimbursementWorkflowFromDraftToApprovalWorks() throws Exception {
        long projectId = createProject();
        long budgetCategoryId = createBudgetCategory(projectId);
        JsonNode completeExpense = createExpense(projectId, budgetCategoryId, "INV-RC-001");
        JsonNode incompleteExpense = createExpense(projectId, budgetCategoryId, null);

        // Both registered expenses are available for reimbursement.
        ResponseEntity<String> availableResponse = restTemplate.getForEntity(
                "/api/reimbursements/available-expenses?projectId=" + projectId, String.class);
        assertThat(availableResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(availableResponse.getBody()).contains("\"id\":" + completeExpense.get("id").asLong());
        assertThat(availableResponse.getBody()).contains("\"id\":" + incompleteExpense.get("id").asLong());

        // Create a draft reimbursement order with both expenses.
        ResponseEntity<String> createResponse = restTemplate.postForEntity(
                "/api/reimbursements",
                jsonRequest(Map.of(
                        "projectId", projectId,
                        "applicant", "测试申请人",
                        "expenseIds", List.of(
                                completeExpense.get("id").asLong(),
                                incompleteExpense.get("id").asLong()),
                        "reason", "Workflow test create")),
                String.class);
        assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode detail = data(createResponse).get("order");
        long reimbursementId = detail.get("id").asLong();
        assertThat(detail.get("status").asText()).isEqualTo("DRAFT");
        assertThat(detail.get("totalAmount").asText()).isEqualTo("300.00");
        assertThat(detail.get("reimbursementNo").asText()).startsWith("R-");

        // Linked expenses disappear from the available list.
        ResponseEntity<String> availableAfterLink = restTemplate.getForEntity(
                "/api/reimbursements/available-expenses?projectId=" + projectId, String.class);
        assertThat(availableAfterLink.getBody())
                .doesNotContain("\"id\":" + completeExpense.get("id").asLong());

        // Material check blocks submission: the second expense misses its invoice number.
        ResponseEntity<String> checkResponse = restTemplate.getForEntity(
                "/api/reimbursements/" + reimbursementId + "/material-check", String.class);
        assertThat(checkResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(checkResponse.getBody()).contains("\"pass\":false");
        assertThat(checkResponse.getBody()).contains("缺少发票号");

        ResponseEntity<String> blockedSubmit = restTemplate.postForEntity(
                "/api/reimbursements/" + reimbursementId + "/submit",
                jsonRequest(Map.of("reason", "Workflow test submit")),
                String.class);
        assertThat(blockedSubmit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blockedSubmit.getBody()).contains("REIMBURSEMENT_MATERIALS_INCOMPLETE");

        // Fill the missing invoice number through the expense API.
        ResponseEntity<String> updateExpenseResponse = restTemplate.exchange(
                "/api/expenses/" + incompleteExpense.get("id").asLong(),
                HttpMethod.PUT,
                jsonRequest(Map.of(
                        "projectId", projectId,
                        "budgetCategoryId", budgetCategoryId,
                        "amount", new BigDecimal("150.00"),
                        "expenseDate", "2026-09-01",
                        "vendor", "Workflow Test Vendor",
                        "invoiceNo", "INV-RC-002",
                        "description", "Workflow test expense",
                        "reason", "Workflow test fix invoice",
                        "version", incompleteExpense.get("version").asInt())),
                String.class);
        assertThat(updateExpenseResponse.getStatusCode().is2xxSuccessful()).isTrue();

        // Check passes now (receipt image warnings do not block).
        ResponseEntity<String> checkAfterFix = restTemplate.getForEntity(
                "/api/reimbursements/" + reimbursementId + "/material-check", String.class);
        assertThat(checkAfterFix.getBody()).contains("\"pass\":true");

        // Submit then approve.
        ResponseEntity<String> submitResponse = restTemplate.postForEntity(
                "/api/reimbursements/" + reimbursementId + "/submit",
                jsonRequest(Map.of("reason", "Workflow test submit")),
                String.class);
        assertThat(submitResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(data(submitResponse).get("order").get("status").asText()).isEqualTo("SUBMITTED");

        ResponseEntity<String> approveResponse = restTemplate.postForEntity(
                "/api/reimbursements/" + reimbursementId + "/approve",
                jsonRequest(Map.of("reason", "Workflow test approve")),
                String.class);
        assertThat(approveResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(data(approveResponse).get("order").get("status").asText()).isEqualTo("APPROVED");
        assertThat(data(approveResponse).get("order").get("approvedAt").isNull()).isFalse();

        // Linked expenses are marked reimbursed.
        ResponseEntity<String> expensePage = restTemplate.getForEntity(
                "/api/expenses?current=1&size=10&projectId=" + projectId + "&status=REIMBURSED",
                String.class);
        assertThat(expensePage.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(expensePage.getBody()).contains("\"total\":2");

        // All transitions are audited.
        ResponseEntity<String> auditResponse = restTemplate.getForEntity(
                "/api/audit-logs?current=1&size=50", String.class);
        assertThat(auditResponse.getBody()).contains("CREATE_REIMBURSEMENT");
        assertThat(auditResponse.getBody()).contains("SUBMIT_REIMBURSEMENT");
        assertThat(auditResponse.getBody()).contains("APPROVE_REIMBURSEMENT");

        // The submit transition enqueued an interactive Feishu card notification.
        ResponseEntity<String> outboxResponse = restTemplate.getForEntity(
                "/api/feishu/notifications?current=1&size=50", String.class);
        assertThat(outboxResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(outboxResponse.getBody()).contains("\"messageType\":\"interactive\"");
        assertThat(outboxResponse.getBody()).contains("reimbursement-submit-" + reimbursementId);
        assertThat(outboxResponse.getBody()).contains("报销单已提交，待审批");
    }

    @Test
    void rejectAllowsResubmissionAndDuplicateLinkIsRefused() throws Exception {
        long projectId = createProject();
        long budgetCategoryId = createBudgetCategory(projectId);
        JsonNode expense = createExpense(projectId, budgetCategoryId, "INV-RJ-001");

        ResponseEntity<String> createResponse = restTemplate.postForEntity(
                "/api/reimbursements",
                jsonRequest(Map.of(
                        "projectId", projectId,
                        "applicant", "测试申请人",
                        "expenseIds", List.of(expense.get("id").asLong()))),
                String.class);
        assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();
        long reimbursementId = data(createResponse).get("order").get("id").asLong();

        // Submit, then reject with a reason.
        assertThat(restTemplate.postForEntity(
                "/api/reimbursements/" + reimbursementId + "/submit",
                jsonRequest(Map.of("reason", "submit for reject test")),
                String.class).getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> rejectResponse = restTemplate.postForEntity(
                "/api/reimbursements/" + reimbursementId + "/reject",
                jsonRequest(Map.of("reason", "材料不清晰，请补充说明")),
                String.class);
        assertThat(rejectResponse.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode rejectedOrder = data(rejectResponse).get("order");
        assertThat(rejectedOrder.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejectedOrder.get("rejectReason").asText()).contains("材料不清晰");

        // The expense falls back to registered and can be picked again.
        ResponseEntity<String> expensePage = restTemplate.getForEntity(
                "/api/expenses?current=1&size=10&projectId=" + projectId + "&status=REGISTERED",
                String.class);
        assertThat(expensePage.getBody()).contains("\"total\":1");

        // A rejected order can be resubmitted.
        ResponseEntity<String> resubmitResponse = restTemplate.postForEntity(
                "/api/reimbursements/" + reimbursementId + "/submit",
                jsonRequest(Map.of("reason", "resubmit after rejection")),
                String.class);
        assertThat(resubmitResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(data(resubmitResponse).get("order").get("status").asText()).isEqualTo("SUBMITTED");

        // The same expense cannot be linked to a second order.
        ResponseEntity<String> duplicateResponse = restTemplate.postForEntity(
                "/api/reimbursements",
                jsonRequest(Map.of(
                        "projectId", projectId,
                        "applicant", "测试申请人",
                        "expenseIds", List.of(expense.get("id").asLong()))),
                String.class);
        assertThat(duplicateResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicateResponse.getBody()).contains("EXPENSE_ALREADY_LINKED");

        // Rejecting without a reason is refused.
        ResponseEntity<String> rejectWithoutReason = restTemplate.postForEntity(
                "/api/reimbursements/" + reimbursementId + "/reject",
                jsonRequest(Map.of("reason", "  ")),
                String.class);
        assertThat(rejectWithoutReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejectWithoutReason.getBody()).contains("TEXT_REQUIRED");
    }

    @Test
    void quickModeCreatesDefaultCategoryAndSubmitNowSkipsDraft() throws Exception {
        // Quick mode is enabled by default: the project comes with a default category.
        ResponseEntity<String> projectResponse = restTemplate.postForEntity(
                "/api/projects",
                jsonRequest(Map.of(
                        "projectCode", "RC-QM-" + UUID.randomUUID(),
                        "projectName", "Quick Mode Project",
                        "totalBudget", new BigDecimal("1000.00"),
                        "status", "ACTIVE")),
                String.class);
        assertThat(projectResponse.getStatusCode().is2xxSuccessful()).isTrue();
        long projectId = data(projectResponse).get("id").asLong();

        ResponseEntity<String> categoriesResponse = restTemplate.getForEntity(
                "/api/projects/" + projectId + "/budget-categories?current=1&size=10", String.class);
        assertThat(categoriesResponse.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode defaultCategory = data(categoriesResponse).get("records").get(0);
        assertThat(defaultCategory.get("categoryCode").asText()).isEqualTo("DEFAULT");
        assertThat(defaultCategory.get("allocatedAmount").asText()).isEqualTo("1000.00");
        long categoryId = defaultCategory.get("id").asLong();

        JsonNode expense = createExpense(projectId, categoryId, "INV-QM-001");

        // Create and submit in one step: complete materials skip the draft stage.
        ResponseEntity<String> createResponse = restTemplate.postForEntity(
                "/api/reimbursements",
                jsonRequest(Map.of(
                        "projectId", projectId,
                        "applicant", "测试申请人",
                        "expenseIds", List.of(expense.get("id").asLong()),
                        "reason", "Create and submit test",
                        "submitNow", true)),
                String.class);
        assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode order = data(createResponse).get("order");
        long reimbursementId = order.get("id").asLong();
        assertThat(order.get("status").asText()).isEqualTo("SUBMITTED");

        // The submit-now path enqueues the Feishu card as well.
        ResponseEntity<String> outboxResponse = restTemplate.getForEntity(
                "/api/feishu/notifications?current=1&size=50", String.class);
        assertThat(outboxResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(outboxResponse.getBody()).contains("reimbursement-submit-" + reimbursementId);

        // Incomplete materials keep the order in draft instead of failing the request.
        JsonNode incompleteExpense = createExpense(projectId, categoryId, null);
        ResponseEntity<String> draftResponse = restTemplate.postForEntity(
                "/api/reimbursements",
                jsonRequest(Map.of(
                        "projectId", projectId,
                        "applicant", "测试申请人",
                        "expenseIds", List.of(incompleteExpense.get("id").asLong()),
                        "reason", "Create and submit with missing materials",
                        "submitNow", true)),
                String.class);
        assertThat(draftResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(data(draftResponse).get("order").get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void voidReleasesLinkedExpensesForReuse() throws Exception {
        long projectId = createProject();
        long budgetCategoryId = createBudgetCategory(projectId);
        JsonNode expense = createExpense(projectId, budgetCategoryId, "INV-VOID-001");

        ResponseEntity<String> createResponse = restTemplate.postForEntity(
                "/api/reimbursements",
                jsonRequest(Map.of(
                        "projectId", projectId,
                        "applicant", "测试申请人",
                        "expenseIds", List.of(expense.get("id").asLong()),
                        "reason", "Void test create")),
                String.class);
        assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();
        long reimbursementId = data(createResponse).get("order").get("id").asLong();

        // Void without a reason is refused.
        ResponseEntity<String> voidWithoutReason = restTemplate.postForEntity(
                "/api/reimbursements/" + reimbursementId + "/void",
                jsonRequest(Map.of("reason", "  ")),
                String.class);
        assertThat(voidWithoutReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(voidWithoutReason.getBody()).contains("TEXT_REQUIRED");

        // Void the draft order.
        ResponseEntity<String> voidResponse = restTemplate.postForEntity(
                "/api/reimbursements/" + reimbursementId + "/void",
                jsonRequest(Map.of("reason", "测试作废释放支出")),
                String.class);
        assertThat(voidResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(data(voidResponse).get("order").get("status").asText()).isEqualTo("VOID");

        // The released expense shows up in the available list again.
        ResponseEntity<String> availableResponse = restTemplate.getForEntity(
                "/api/reimbursements/available-expenses?projectId=" + projectId, String.class);
        assertThat(availableResponse.getBody()).contains("\"id\":" + expense.get("id").asLong());

        // The released expense can be linked to a new order.
        ResponseEntity<String> recreateResponse = restTemplate.postForEntity(
                "/api/reimbursements",
                jsonRequest(Map.of(
                        "projectId", projectId,
                        "applicant", "测试申请人",
                        "expenseIds", List.of(expense.get("id").asLong()),
                        "reason", "Relink after void")),
                String.class);
        assertThat(recreateResponse.getStatusCode().is2xxSuccessful()).isTrue();
        long secondOrderId = data(recreateResponse).get("order").get("id").asLong();

        // A voided order is terminal: submit is refused.
        ResponseEntity<String> submitVoided = restTemplate.postForEntity(
                "/api/reimbursements/" + reimbursementId + "/submit",
                jsonRequest(Map.of("reason", "submit voided")),
                String.class);
        assertThat(submitVoided.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(submitVoided.getBody()).contains("REIMBURSEMENT_STATUS_CONFLICT");

        // Voiding the new order releases the expense once more.
        assertThat(restTemplate.postForEntity(
                "/api/reimbursements/" + secondOrderId + "/void",
                jsonRequest(Map.of("reason", "测试再次作废")),
                String.class).getStatusCode().is2xxSuccessful()).isTrue();

        // The void transition is audited.
        ResponseEntity<String> auditResponse = restTemplate.getForEntity(
                "/api/audit-logs?current=1&size=50", String.class);
        assertThat(auditResponse.getBody()).contains("VOID_REIMBURSEMENT");
    }

    @Test
    void draftUpdateRecalculatesTotalAndGuardsVersion() throws Exception {
        long projectId = createProject();
        long budgetCategoryId = createBudgetCategory(projectId);
        JsonNode firstExpense = createExpense(projectId, budgetCategoryId, "INV-UPD-001");
        JsonNode secondExpense = createExpense(projectId, budgetCategoryId, "INV-UPD-002");

        ResponseEntity<String> createResponse = restTemplate.postForEntity(
                "/api/reimbursements",
                jsonRequest(Map.of(
                        "projectId", projectId,
                        "applicant", "测试申请人",
                        "expenseIds", List.of(firstExpense.get("id").asLong()),
                        "reason", "Update test create")),
                String.class);
        assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode order = data(createResponse).get("order");
        long reimbursementId = order.get("id").asLong();

        // Edit mode: with excludeOrderId the order's own expense stays selectable.
        ResponseEntity<String> availableForEdit = restTemplate.getForEntity(
                "/api/reimbursements/available-expenses?projectId=" + projectId
                        + "&excludeOrderId=" + reimbursementId,
                String.class);
        assertThat(availableForEdit.getBody()).contains("\"id\":" + firstExpense.get("id").asLong());
        assertThat(availableForEdit.getBody()).contains("\"id\":" + secondExpense.get("id").asLong());

        // Swap the first expense for the second and rename the applicant.
        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/reimbursements/" + reimbursementId,
                HttpMethod.PUT,
                jsonRequest(Map.of(
                        "applicant", "李四",
                        "expenseIds", List.of(secondExpense.get("id").asLong()),
                        "reason", "测试修改草稿",
                        "version", order.get("version").asInt())),
                String.class);
        assertThat(updateResponse.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode updatedOrder = data(updateResponse).get("order");
        assertThat(updatedOrder.get("applicant").asText()).isEqualTo("李四");
        assertThat(updatedOrder.get("totalAmount").asText()).isEqualTo("150.00");
        assertThat(data(updateResponse).get("items").get(0).get("expenseId").asLong())
                .isEqualTo(secondExpense.get("id").asLong());

        // The removed expense is released again.
        ResponseEntity<String> availableAfterUpdate = restTemplate.getForEntity(
                "/api/reimbursements/available-expenses?projectId=" + projectId, String.class);
        assertThat(availableAfterUpdate.getBody()).contains("\"id\":" + firstExpense.get("id").asLong());

        // Updating with a stale version is refused.
        ResponseEntity<String> staleUpdate = restTemplate.exchange(
                "/api/reimbursements/" + reimbursementId,
                HttpMethod.PUT,
                jsonRequest(Map.of(
                        "applicant", "王五",
                        "expenseIds", List.of(secondExpense.get("id").asLong()),
                        "reason", "stale version",
                        "version", order.get("version").asInt())),
                String.class);
        assertThat(staleUpdate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(staleUpdate.getBody()).contains("REIMBURSEMENT_STATUS_CONFLICT");

        // The update transition is audited.
        ResponseEntity<String> auditResponse = restTemplate.getForEntity(
                "/api/audit-logs?current=1&size=50", String.class);
        assertThat(auditResponse.getBody()).contains("UPDATE_REIMBURSEMENT");
    }

    @Test
    void quickExpenseEntryRegistersExpensesAndSubmitsInOneStep() throws Exception {
        long projectId = createProject();
        long budgetCategoryId = createBudgetCategory(projectId);

        ResponseEntity<String> createResponse = restTemplate.postForEntity(
                "/api/reimbursements",
                jsonRequest(Map.of(
                        "projectId", projectId,
                        "applicant", "张三",
                        "newExpenses", List.of(Map.of(
                                "budgetCategoryId", budgetCategoryId,
                                "amount", new BigDecimal("50.00"),
                                "expenseDate", "2026-09-02",
                                "vendor", "文具店",
                                "invoiceNo", "INV-QUICK-001",
                                "description", "购买 A4 纸")),
                        "reason", "Quick entry test",
                        "submitNow", true)),
                String.class);
        assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode detail = data(createResponse);
        JsonNode order = detail.get("order");
        long reimbursementId = order.get("id").asLong();
        assertThat(order.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(order.get("totalAmount").asText()).isEqualTo("50.00");
        assertThat(detail.get("items")).hasSize(1);
        long quickExpenseId = detail.get("items").get(0).get("expenseId").asLong();

        // The quick expense was registered and occupies the category budget.
        ResponseEntity<String> expenseResponse = restTemplate.getForEntity(
                "/api/expenses/" + quickExpenseId, String.class);
        assertThat(expenseResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(data(expenseResponse).get("status").asText()).isEqualTo("REGISTERED");

        ResponseEntity<String> categoryResponse = restTemplate.getForEntity(
                "/api/projects/" + projectId + "/budget-categories?current=1&size=10", String.class);
        assertThat(data(categoryResponse).get("records").get(0).get("usedAmount").asText())
                .isEqualTo("50.00");

        // Both the expense registration and the order creation are audited.
        ResponseEntity<String> auditResponse = restTemplate.getForEntity(
                "/api/audit-logs?current=1&size=50", String.class);
        assertThat(auditResponse.getBody()).contains("CREATE_EXPENSE");
        assertThat(auditResponse.getBody()).contains("CREATE_REIMBURSEMENT");

        // The submit transition pushed the Feishu card.
        ResponseEntity<String> outboxResponse = restTemplate.getForEntity(
                "/api/feishu/notifications?current=1&size=50", String.class);
        assertThat(outboxResponse.getBody()).contains("reimbursement-submit-" + reimbursementId);
    }

    @Test
    void approveAndRejectPushResultCardsToOutbox() throws Exception {
        long projectId = createProject();
        long budgetCategoryId = createBudgetCategory(projectId);
        JsonNode approvedExpense = createExpense(projectId, budgetCategoryId, "INV-NTF-001");
        JsonNode rejectedExpense = createExpense(projectId, budgetCategoryId, "INV-NTF-002");

        // Approve path.
        long approvedOrderId = createSubmittedOrder(projectId, approvedExpense.get("id").asLong());
        ResponseEntity<String> approveResponse = restTemplate.postForEntity(
                "/api/reimbursements/" + approvedOrderId + "/approve",
                jsonRequest(Map.of("reason", "Notification test approve")),
                String.class);
        assertThat(approveResponse.getStatusCode().is2xxSuccessful()).isTrue();

        // Reject path.
        long rejectedOrderId = createSubmittedOrder(projectId, rejectedExpense.get("id").asLong());
        ResponseEntity<String> rejectResponse = restTemplate.postForEntity(
                "/api/reimbursements/" + rejectedOrderId + "/reject",
                jsonRequest(Map.of("reason", "Notification test reject")),
                String.class);
        assertThat(rejectResponse.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> outboxResponse = restTemplate.getForEntity(
                "/api/feishu/notifications?current=1&size=50", String.class);
        assertThat(outboxResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(outboxResponse.getBody()).contains("reimbursement-approve-" + approvedOrderId);
        assertThat(outboxResponse.getBody()).contains("reimbursement-reject-" + rejectedOrderId);
        assertThat(outboxResponse.getBody()).contains("报销单已通过");
        assertThat(outboxResponse.getBody()).contains("报销单已驳回");
    }

    private long createSubmittedOrder(long projectId, long expenseId) throws Exception {
        ResponseEntity<String> createResponse = restTemplate.postForEntity(
                "/api/reimbursements",
                jsonRequest(Map.of(
                        "projectId", projectId,
                        "applicant", "测试申请人",
                        "expenseIds", List.of(expenseId),
                        "reason", "Notification fixture",
                        "submitNow", true)),
                String.class);
        assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode order = data(createResponse).get("order");
        assertThat(order.get("status").asText()).isEqualTo("SUBMITTED");
        return order.get("id").asLong();
    }

    // ---------- fixtures ----------

    private long createProject() throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/projects",
                jsonRequest(Map.of(
                        "projectCode", "RC-" + UUID.randomUUID(),
                        "projectName", "Reimbursement Workflow Project",
                        "principalInvestigator", "Test PI",
                        "fundingSource", "NSFC",
                        "totalBudget", new BigDecimal("100000.00"),
                        "startDate", "2026-01-01",
                        "endDate", "2029-12-31",
                        "status", "ACTIVE",
                        "quickMode", false)),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return data(response).get("id").asLong();
    }

    private long createBudgetCategory(long projectId) throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/projects/" + projectId + "/budget-categories",
                jsonRequest(Map.of(
                        "categoryCode", "RC-" + UUID.randomUUID().toString().substring(0, 8),
                        "categoryName", "Reimbursement Test Category",
                        "allocatedAmount", new BigDecimal("50000.00"),
                        "status", "ACTIVE")),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return data(response).get("id").asLong();
    }

    private JsonNode createExpense(long projectId, long budgetCategoryId, String invoiceNo) throws Exception {
        java.util.HashMap<String, Object> body = new java.util.HashMap<>();
        body.put("projectId", projectId);
        body.put("budgetCategoryId", budgetCategoryId);
        body.put("amount", new BigDecimal("150.00"));
        body.put("expenseDate", "2026-09-01");
        body.put("vendor", "Workflow Test Vendor");
        body.put("description", "Workflow test expense");
        body.put("reason", "Workflow test fixture");
        if (invoiceNo != null) {
            body.put("invoiceNo", invoiceNo);
        }
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/expenses", jsonRequest(body), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return data(response);
    }

    private HttpEntity<Map<String, Object>> jsonRequest(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", "test-user");
        headers.set("X-User-Name", "Test User");
        headers.set("X-Tenant-Id", "test");
        return new HttpEntity<>(body, headers);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("data");
    }
}
