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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.service.JwtTokenService;
import com.rcdis.agent.support.TestAuth;

/**
 * Integration tests for the in-app notification pipeline: submit receipt plus approver todo,
 * approve/reject results, void notice and the read acknowledgement endpoints.
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "rcdis.feishu.enabled=false",
                "rcdis.feishu.client-type=noop"
        })
class InAppNotificationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void authenticateAsAdmin() {
        applyAdmin();
    }

    @Test
    void submitAndApproveProduceApplicantReceipts() throws Exception {
        long projectId = createProject("1000.00");
        long before = unreadCount();

        long orderId = createOrder(projectId, false);
        assertThat(post("/api/reimbursements/" + orderId + "/submit", Map.of("reason", "submit"))
                .getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(unreadCount()).isGreaterThanOrEqualTo(before + 1);

        assertThat(post("/api/reimbursements/" + orderId + "/approve", Map.of("reason", "approve"))
                .getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(unreadCount()).isGreaterThanOrEqualTo(before + 2);

        long rejectedOrderId = createOrder(projectId, true);
        assertThat(post("/api/reimbursements/" + rejectedOrderId + "/reject", Map.of("reason", "材料不全"))
                .getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(unreadCount()).isGreaterThanOrEqualTo(before + 3);

        JsonNode list = notificationList();
        assertThat(list.get("records").toString()).contains("SUBMITTED", "APPROVED", "REJECTED");

        // Acknowledging one notification decrements the unread counter by exactly one.
        long firstUnreadId = list.get("records").get(0).get("id").asLong();
        assertThat(put("/api/notifications/" + firstUnreadId + "/read").getStatusCode().is2xxSuccessful())
                .isTrue();

        assertThat(put("/api/notifications/read-all").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(unreadCount()).isZero();
    }

    @Test
    void submissionPushesTodoToApprovalRoleHolders() throws Exception {
        // The seeded "approver" account holds the APPROVER role without any Feishu binding; the
        // todo must follow the same role source that authorizes the approve endpoint.
        long projectId = createProject("1000.00");
        long orderId = createOrder(projectId, true);
        assertThat(orderId).isPositive();

        applyBearer(TestAuth.token(jwtTokenService, "approver", "test", TestAuth.APPROVER));
        try {
            JsonNode list = notificationList();
            assertThat(list.get("records").toString()).contains("SUBMITTED");
            assertThat(unreadCount()).isGreaterThanOrEqualTo(1);
        } finally {
            applyAdmin();
        }
    }

    @Test
    void voidOrderNotifiesApplicant() throws Exception {
        long projectId = createProject("1000.00");
        long orderId = createOrder(projectId, false);
        long before = unreadCount();

        assertThat(post("/api/reimbursements/" + orderId + "/void", Map.of("reason", "测试作废"))
                .getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(unreadCount()).isGreaterThanOrEqualTo(before + 1);
        assertThat(notificationList().get("records").toString()).contains("VOIDED");
    }

    @Test
    void publicPaymentSubmitCreatesNoInAppNotification() throws Exception {
        long projectId = createProject("1000.00");
        long before = unreadCount();

        // public_payment books directly at submit without an approval flow, so no lifecycle
        // notification is pushed for it in v1.
        createOrder(projectId, true, "public_payment");
        assertThat(unreadCount()).isEqualTo(before);
    }

    // ---------- fixtures ----------

    private long createOrder(long projectId, boolean submitNow) throws Exception {
        return createOrder(projectId, submitNow, "reimbursement");
    }

    private long createOrder(long projectId, boolean submitNow, String paymentType) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("applicant", "测试申请人");
        body.put("paymentType", paymentType);
        body.put("items", List.of("public_payment".equals(paymentType)
                ? publicItem("100.00") : completeItem("100.00")));
        body.put("reason", "notification test");
        body.put("submitNow", submitNow);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/reimbursements", jsonRequest(body), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return data(response).get("order").get("id").asLong();
    }

    private long createProject(String totalBudget) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectCode", "RC-" + UUID.randomUUID());
        body.put("projectName", "Notification Test Project");
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
        item.put("vendor", "Notification Vendor");
        item.put("invoiceNo", "INV-" + UUID.randomUUID().toString().substring(0, 8));
        item.put("description", "Notification test expense");
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

    private long unreadCount() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/notifications/unread-count", String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return data(response).asLong();
    }

    private JsonNode notificationList() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/notifications?current=1&size=20", String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return data(response);
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        return restTemplate.postForEntity(path, jsonRequest(body), String.class);
    }

    private ResponseEntity<String> put(String path) {
        return restTemplate.exchange(path, org.springframework.http.HttpMethod.PUT,
                new HttpEntity<>(new HttpHeaders()), String.class);
    }

    private HttpEntity<Map<String, Object>> jsonRequest(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("data");
    }

    private void applyAdmin() {
        TestAuth.applyBearer(restTemplate, TestAuth.adminToken(jwtTokenService));
    }

    private void applyBearer(String token) {
        TestAuth.applyBearer(restTemplate, token);
    }
}
