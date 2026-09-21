package com.rcdis.agent;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.service.JwtTokenService;
import com.rcdis.agent.support.TestAuth;

/**
 * End-to-end workflow tests for the simplified single-reimbursement model:
 * inline line items, project-level freeze-at-submit / consume-at-approve budget,
 * public payment booking at submit, withdraw, void reversal and the material gate.
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

    @Autowired
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void authenticateAsAdmin() {
        TestAuth.applyBearer(restTemplate, TestAuth.adminToken(jwtTokenService));
    }

    @Test
    void reimbursementSubmitFreezesAndApproveConsumesProjectBudget() throws Exception {
        long projectId = createProject("1000.00");
        JsonNode created = createOrder(projectId, "reimbursement", List.of(completeItem("150.00")), false);
        long reimbursementId = created.get("order").get("id").asLong();
        assertThat(created.get("order").get("status").asText()).isEqualTo("draft");
        // Draft holds no budget.
        assertThat(frozenOf(projectId)).isEqualTo("0.00");
        assertThat(usedOf(projectId)).isEqualTo("0.00");

        assertThat(post("/api/reimbursements/" + reimbursementId + "/submit",
                Map.of("reason", "submit")).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(frozenOf(projectId)).isEqualTo("150.00");
        assertThat(usedOf(projectId)).isEqualTo("0.00");

        ResponseEntity<String> approve = post("/api/reimbursements/" + reimbursementId + "/approve",
                Map.of("reason", "approve"));
        assertThat(approve.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(data(approve).get("order").get("status").asText()).isEqualTo("approved");
        assertThat(frozenOf(projectId)).isEqualTo("0.00");
        assertThat(usedOf(projectId)).isEqualTo("150.00");

        String audit = restTemplate.getForEntity("/api/audit-logs?current=1&size=50", String.class).getBody();
        assertThat(audit).contains("CREATE_REIMBURSEMENT", "SUBMIT_REIMBURSEMENT", "APPROVE_REIMBURSEMENT");
    }

    @Test
    void reimbursementSubmitRefusedWhenMaterialsMissing() throws Exception {
        long projectId = createProject("1000.00");
        // Missing vendor and invoice/receipt for a reimbursement line.
        JsonNode created = createOrder(projectId, "reimbursement", List.of(incompleteItem("80.00")), false);
        long reimbursementId = created.get("order").get("id").asLong();

        ResponseEntity<String> submit = post("/api/reimbursements/" + reimbursementId + "/submit",
                Map.of("reason", "should fail"));
        assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(submit.getBody()).contains("REIMBURSEMENT_MATERIALS_INCOMPLETE");
        assertThat(frozenOf(projectId)).isEqualTo("0.00");
    }

    @Test
    void publicPaymentBooksBudgetDirectlyAtSubmit() throws Exception {
        long projectId = createProject("1000.00");
        JsonNode created = createOrder(projectId, "public_payment", List.of(publicItem("300.00")), true);
        JsonNode order = created.get("order");
        assertThat(order.get("status").asText()).isEqualTo("approved");
        assertThat(order.get("paymentType").asText()).isEqualTo("public_payment");
        assertThat(usedOf(projectId)).isEqualTo("300.00");
        assertThat(frozenOf(projectId)).isEqualTo("0.00");
    }

    @Test
    void publicPaymentVoidReversesUsedBudget() throws Exception {
        long projectId = createProject("1000.00");
        JsonNode created = createOrder(projectId, "public_payment", List.of(publicItem("200.00")), true);
        long reimbursementId = created.get("order").get("id").asLong();
        assertThat(usedOf(projectId)).isEqualTo("200.00");

        ResponseEntity<String> voidResponse = post("/api/reimbursements/" + reimbursementId + "/void",
                Map.of("reason", "冲销错误入账"));
        assertThat(voidResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(data(voidResponse).get("order").get("status").asText()).isEqualTo("void");
        assertThat(usedOf(projectId)).isEqualTo("0.00");
    }

    @Test
    void withdrawReturnsFrozenBudgetToAvailable() throws Exception {
        long projectId = createProject("1000.00");
        JsonNode created = createOrder(projectId, "reimbursement", List.of(completeItem("120.00")), true);
        long reimbursementId = created.get("order").get("id").asLong();
        assertThat(created.get("order").get("status").asText()).isEqualTo("submitted");
        assertThat(frozenOf(projectId)).isEqualTo("120.00");

        ResponseEntity<String> withdraw = post("/api/reimbursements/" + reimbursementId + "/withdraw",
                Map.of("reason", "撤回修改"));
        assertThat(withdraw.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(data(withdraw).get("order").get("status").asText()).isEqualTo("draft");
        assertThat(frozenOf(projectId)).isEqualTo("0.00");
    }

    @Test
    void submitOverAvailableBudgetIsRefused() throws Exception {
        long projectId = createProject("100.00");
        JsonNode created = createOrder(projectId, "reimbursement", List.of(completeItem("500.00")), false);
        long reimbursementId = created.get("order").get("id").asLong();

        ResponseEntity<String> submit = post("/api/reimbursements/" + reimbursementId + "/submit",
                Map.of("reason", "overspend"));
        assertThat(submit.getStatusCode()).isNotEqualTo(HttpStatus.OK);
        assertThat(submit.getBody()).contains("BUDGET_PROJECT_AVAILABLE_AMOUNT_EXCEEDED");
        assertThat(frozenOf(projectId)).isEqualTo("0.00");
    }

    @Test
    void draftUpdateRecalculatesTotalAndGuardsVersion() throws Exception {
        long projectId = createProject("1000.00");
        JsonNode created = createOrder(projectId, "reimbursement", List.of(completeItem("100.00")), false);
        long reimbursementId = created.get("order").get("id").asLong();
        int version = created.get("order").get("version").asInt();

        Map<String, Object> updateBody = new HashMap<>();
        updateBody.put("applicant", "李四");
        updateBody.put("items", List.of(completeItem("60.00"), completeItem("40.00")));
        updateBody.put("reason", "测试修改草稿");
        updateBody.put("version", version);
        ResponseEntity<String> update = restTemplate.exchange(
                "/api/reimbursements/" + reimbursementId, HttpMethod.PUT, jsonRequest(updateBody), String.class);
        assertThat(update.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(data(update).get("order").get("applicant").asText()).isEqualTo("李四");
        assertThat(data(update).get("order").get("totalAmount").asText()).isEqualTo("100.00");
        assertThat(data(update).get("items")).hasSize(2);

        // Stale version is refused.
        ResponseEntity<String> stale = restTemplate.exchange(
                "/api/reimbursements/" + reimbursementId, HttpMethod.PUT, jsonRequest(updateBody), String.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).contains("REIMBURSEMENT_STATUS_CONFLICT");
    }

    @Test
    void approveAndRejectPushResultCardsToOutbox() throws Exception {
        long projectId = createProject("1000.00");
        long approvedOrderId = submitReimbursement(projectId, "150.00");
        assertThat(post("/api/reimbursements/" + approvedOrderId + "/approve",
                Map.of("reason", "approve")).getStatusCode().is2xxSuccessful()).isTrue();

        long rejectedOrderId = submitReimbursement(projectId, "150.00");
        assertThat(post("/api/reimbursements/" + rejectedOrderId + "/reject",
                Map.of("reason", "reject")).getStatusCode().is2xxSuccessful()).isTrue();

        String outbox = restTemplate.getForEntity("/api/feishu/notifications?current=1&size=50", String.class).getBody();
        assertThat(outbox).contains("reimbursement-approve-" + approvedOrderId);
        assertThat(outbox).contains("reimbursement-reject-" + rejectedOrderId);
    }

    // ---------- fixtures ----------

    private long submitReimbursement(long projectId, String amount) throws Exception {
        JsonNode created = createOrder(projectId, "reimbursement", List.of(completeItem(amount)), true);
        assertThat(created.get("order").get("status").asText()).isEqualTo("submitted");
        return created.get("order").get("id").asLong();
    }

    private JsonNode createOrder(long projectId, String paymentType, List<Map<String, Object>> items,
            boolean submitNow) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("applicant", "测试申请人");
        body.put("paymentType", paymentType);
        body.put("items", items);
        body.put("reason", "workflow test");
        body.put("submitNow", submitNow);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/reimbursements", jsonRequest(body), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return data(response);
    }

    private long createProject(String totalBudget) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectCode", "RC-" + UUID.randomUUID());
        body.put("projectName", "Reimbursement Workflow Project");
        body.put("principalInvestigator", "Test PI");
        body.put("fundingSource", "NSFC");
        body.put("totalBudget", new BigDecimal(totalBudget));
        body.put("startDate", "2026-01-01");
        body.put("endDate", "2029-12-31");
        body.put("status", "ACTIVE");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/projects", jsonRequest(body), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return data(response).get("id").asLong();
    }

    private Map<String, Object> completeItem(String amount) {
        Map<String, Object> item = new HashMap<>();
        item.put("amount", new BigDecimal(amount));
        item.put("expenseDate", "2026-09-01");
        item.put("vendor", "Workflow Vendor");
        item.put("invoiceNo", "INV-" + UUID.randomUUID().toString().substring(0, 8));
        item.put("description", "Workflow test expense");
        return item;
    }

    private Map<String, Object> incompleteItem(String amount) {
        Map<String, Object> item = new HashMap<>();
        item.put("amount", new BigDecimal(amount));
        item.put("expenseDate", "2026-09-01");
        item.put("description", "缺材料的报销行");
        return item;
    }

    private Map<String, Object> publicItem(String amount) {
        Map<String, Object> item = new HashMap<>();
        item.put("amount", new BigDecimal(amount));
        item.put("expenseDate", "2026-09-01");
        item.put("description", "公卡直接采购");
        item.put("counterpartyAccount", "6222020000001234567");
        return item;
    }

    private String usedOf(long projectId) throws Exception {
        return projectNode(projectId).get("usedAmount").asText();
    }

    private String frozenOf(long projectId) throws Exception {
        return projectNode(projectId).get("frozenAmount").asText();
    }

    private JsonNode projectNode(long projectId) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/projects/" + projectId, String.class);
        return data(response);
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        return restTemplate.postForEntity(path, jsonRequest(body), String.class);
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
