package com.rcdis.agent;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.util.HashUtils;
import com.rcdis.agent.entity.FeishuCallbackEventEntity;
import com.rcdis.agent.infrastructure.feishu.CardActionTokenSupport;
import com.rcdis.agent.mapper.FeishuCallbackEventMapper;
import com.rcdis.agent.service.JwtTokenService;
import com.rcdis.agent.support.TestAuth;

/**
 * Feishu interactive approval card tests.
 *
 * <p>Driven through the HTTP callback adapter with correctly signed payloads, which exercises the
 * whole chain offline: signature verification, url verification handshake, idempotency, action token
 * tamper detection, approver authorization, self-approval blocking, both decisions and the card that
 * replaces the approval card in place. The WebSocket adapter normalizes into the same request object,
 * so these guarantees carry over to it.</p>
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "rcdis.feishu.enabled=false",
                "rcdis.feishu.client-type=noop",
                "rcdis.feishu.encrypt-key=test-encrypt-key-for-card-callback",
                "rcdis.feishu.verification-token=test-verification-token",
                "rcdis.feishu.card-action-signing-key=test-card-action-signing-key",
                "rcdis.feishu.default-receive-id=oc_test_chat",
                "rcdis.feishu.default-receive-id-type=chat_id"
        })
class FeishuCardCallbackTests {

    private static final String CALLBACK_URL = "/api/feishu/card-callback";
    private static final String ENCRYPT_KEY = "test-encrypt-key-for-card-callback";
    private static final String VERIFICATION_TOKEN = "test-verification-token";
    private static final String EVENT_TYPE = "card.action.trigger";
    private static final String DEFAULT_REJECT_REASON = "请检查材料后再次提交";

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

    @Autowired
    private CardActionTokenSupport cardActionTokenSupport;

    @Autowired
    private FeishuCallbackEventMapper callbackEventMapper;

    @Test
    void unauthenticatedCallbacksAreRejected() throws Exception {
        Map<String, Object> actionValue = new LinkedHashMap<>();
        actionValue.put("action", "approve");
        String body = callbackBody("evt-unauthenticated", "ou_nobody", actionValue, null);

        // No signature headers at all.
        HttpHeaders plain = new HttpHeaders();
        plain.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> unsigned = restTemplate.postForEntity(
                CALLBACK_URL, new HttpEntity<>(body, plain), String.class);
        assertThat(unsigned.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // A forged signature.
        ResponseEntity<String> forged = restTemplate.postForEntity(
                CALLBACK_URL, signed(body, "0000000000000000000000000000000000000000000000000000000000000000"),
                String.class);
        assertThat(forged.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // The rejection must not reveal which check failed.
        assertThat(forged.getBody()).doesNotContain("signature").doesNotContain("token");

        // A valid signature but the wrong verification token.
        String wrongTokenBody = callbackBody("evt-wrong-token", "ou_nobody", actionValue, null)
                .replace(VERIFICATION_TOKEN, "another-token");
        ResponseEntity<String> wrongToken = restTemplate.postForEntity(
                CALLBACK_URL, signed(wrongTokenBody, null), String.class);
        assertThat(wrongToken.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(callbackEventMapper.selectCount(new LambdaQueryWrapper<FeishuCallbackEventEntity>()
                .eq(FeishuCallbackEventEntity::getEventId, "evt-unauthenticated"))).isZero();
    }

    @Test
    void urlVerificationChallengeIsEchoedOnlyForTheRightToken() throws Exception {
        Map<String, Object> challenge = new LinkedHashMap<>();
        challenge.put("type", "url_verification");
        challenge.put("challenge", "ajls384kdjx98XX");
        challenge.put("token", VERIFICATION_TOKEN);

        ResponseEntity<String> response = restTemplate.postForEntity(
                CALLBACK_URL,
                signed(objectMapper.writeValueAsString(challenge), null),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(callbackBody(response).get("challenge").asText()).isEqualTo("ajls384kdjx98XX");

        challenge.put("token", "wrong-token");
        ResponseEntity<String> rejected = restTemplate.postForEntity(
                CALLBACK_URL,
                signed(objectMapper.writeValueAsString(challenge), null),
                String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void approveFromCardCommitsAndReplacesTheCardWithAButtonFreeOne() throws Exception {
        String approverOpenId = "ou_approver_" + UUID.randomUUID().toString().substring(0, 8);
        bindApprover(approverOpenId, "user-li", "李四");
        SubmittedOrder order = createSubmittedOrder("张三");

        String eventId = "evt-approve-" + UUID.randomUUID();
        ResponseEntity<String> response = restTemplate.postForEntity(
                CALLBACK_URL,
                signed(callbackBody(eventId, approverOpenId, actionValue(order, "approve"), null), null),
                String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode result = callbackBody(response);
        assertThat(result.get("toast").get("type").asText()).isEqualTo("success");
        assertThat(result.get("toast").get("content").asText()).contains("已通过报销单");

        // The replacement card is card JSON 2.0 (Feishu rejects downgrading to 1.0) and carries no
        // button, so the same card cannot be clicked a second time.
        JsonNode card = result.get("card");
        assertThat(card.get("type").asText()).isEqualTo("raw");
        assertThat(card.get("data").get("schema").asText()).isEqualTo("2.0");
        assertThat(card.get("data").get("header").get("template").asText()).isEqualTo("green");
        assertThat(card.toString()).doesNotContain("\"button\"").doesNotContain("behaviors");
        assertThat(card.toString()).contains("李四");

        assertThat(statusOf(order.id())).isEqualTo("approved");
        assertThat(callbackEvent(eventId).getProcessed()).isTrue();

        String auditBody = restTemplate.getForEntity("/api/audit-logs?current=1&size=100", String.class).getBody();
        assertThat(auditBody).contains("APPROVE_REIMBURSEMENT");
    }

    @Test
    void rejectFromCardUsesTheSubmittedReasonOrDefaults() throws Exception {
        // With a reason typed into the card's optional input.
        String approverOpenId = "ou_approver_" + UUID.randomUUID().toString().substring(0, 8);
        bindApprover(approverOpenId, "user-wang", "王五");
        SubmittedOrder withReason = createSubmittedOrder("张三");
        Map<String, Object> formValue = new LinkedHashMap<>();
        formValue.put("reject_reason", "缺少发票原件，请补充");

        ResponseEntity<String> response = restTemplate.postForEntity(
                CALLBACK_URL,
                signed(callbackBody("evt-reject-reason-" + UUID.randomUUID(), approverOpenId,
                        actionValue(withReason, "reject"), formValue), null),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(callbackBody(response).get("toast").get("content").asText()).contains("已驳回报销单");
        assertThat(statusOf(withReason.id())).isEqualTo("rejected");
        assertThat(rejectReasonOf(withReason.id())).isEqualTo("缺少发票原件，请补充");

        // Without a reason: the fixed default keeps the column and the audit trail meaningful.
        SubmittedOrder withoutReason = createSubmittedOrder("赵六");
        ResponseEntity<String> defaultResponse = restTemplate.postForEntity(
                CALLBACK_URL,
                signed(callbackBody("evt-reject-default-" + UUID.randomUUID(), approverOpenId,
                        actionValue(withoutReason, "reject"), null), null),
                String.class);
        assertThat(defaultResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(callbackBody(defaultResponse).get("card").get("data").get("header").get("template").asText())
                .isEqualTo("red");
        assertThat(statusOf(withoutReason.id())).isEqualTo("rejected");
        assertThat(rejectReasonOf(withoutReason.id())).isEqualTo(DEFAULT_REJECT_REASON);
    }

    @Test
    void tamperedActionValuesAreRejectedWithoutChangingState() throws Exception {
        String approverOpenId = "ou_approver_" + UUID.randomUUID().toString().substring(0, 8);
        bindApprover(approverOpenId, "user-zhao", "赵审批");
        SubmittedOrder order = createSubmittedOrder("张三");
        SubmittedOrder other = createSubmittedOrder("钱七");

        // A token minted for one order must not authorize a different order.
        Map<String, Object> swapped = actionValue(order, "approve");
        swapped.put("reimbursementId", String.valueOf(other.id()));
        assertThat(postAction("evt-swap-" + UUID.randomUUID(), approverOpenId, swapped, null))
                .contains("操作凭证校验失败");
        assertThat(statusOf(order.id())).isEqualTo("submitted");
        assertThat(statusOf(other.id())).isEqualTo("submitted");

        // Flipping approve into reject must fail too, even though both need authorization anyway.
        Map<String, Object> flipped = actionValue(order, "approve");
        flipped.put("action", "reject");
        assertThat(postAction("evt-flip-" + UUID.randomUUID(), approverOpenId, flipped, null))
                .contains("操作凭证校验失败");
        assertThat(statusOf(order.id())).isEqualTo("submitted");

        // A card rendered for an earlier submission of the same order must stop working.
        Map<String, Object> stale = actionValue(order, "approve");
        stale.put("submittedAt", String.valueOf(submittedAtEpochSecond(order) - 1));
        assertThat(postAction("evt-stale-" + UUID.randomUUID(), approverOpenId, stale, null))
                .contains("操作凭证校验失败");
        assertThat(statusOf(order.id())).isEqualTo("submitted");
    }

    @Test
    void unboundAndSelfApprovingClickersAreRejected() throws Exception {
        SubmittedOrder order = createSubmittedOrder("张三");

        // Somebody who is not on the approver list at all.
        assertThat(postAction("evt-unbound-" + UUID.randomUUID(), "ou_stranger_" + UUID.randomUUID(),
                actionValue(order, "approve"), null))
                .contains("不在审批人名单中");
        assertThat(statusOf(order.id())).isEqualTo("submitted");

        // A bound approver must not be able to approve their own order.
        String selfOpenId = "ou_self_" + UUID.randomUUID().toString().substring(0, 8);
        bindApprover(selfOpenId, "user-zhang", "张三");
        assertThat(postAction("evt-self-" + UUID.randomUUID(), selfOpenId, actionValue(order, "approve"), null))
                .contains("不能审批本人提交的报销单");
        assertThat(statusOf(order.id())).isEqualTo("submitted");

        // A disabled binding loses its authority immediately.
        String disabledOpenId = "ou_disabled_" + UUID.randomUUID().toString().substring(0, 8);
        long approverId = bindApprover(disabledOpenId, "user-off", "孙停用");
        toggleApprover(approverId);
        assertThat(postAction("evt-disabled-" + UUID.randomUUID(), disabledOpenId,
                actionValue(order, "approve"), null))
                .contains("不在审批人名单中");
        assertThat(statusOf(order.id())).isEqualTo("submitted");
    }

    @Test
    void aRetriedCallbackIsAnsweredWithoutExecutingTwice() throws Exception {
        String approverOpenId = "ou_approver_" + UUID.randomUUID().toString().substring(0, 8);
        bindApprover(approverOpenId, "user-repeat", "周重复");
        SubmittedOrder order = createSubmittedOrder("张三");
        String eventId = "evt-repeat-" + UUID.randomUUID();
        String body = callbackBody(eventId, approverOpenId, actionValue(order, "approve"), null);

        ResponseEntity<String> first = restTemplate.postForEntity(CALLBACK_URL, signed(body, null), String.class);
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(callbackBody(first).get("toast").get("type").asText()).isEqualTo("success");
        assertThat(statusOf(order.id())).isEqualTo("approved");

        // Feishu retries when it does not get an answer within 3 seconds.
        ResponseEntity<String> second = restTemplate.postForEntity(CALLBACK_URL, signed(body, null), String.class);
        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(callbackBody(second).get("toast").get("content").asText()).contains("此前已处理");
        assertThat(callbackBody(second).has("card")).isFalse();
        assertThat(statusOf(order.id())).isEqualTo("approved");

        assertThat(callbackEventMapper.selectCount(new LambdaQueryWrapper<FeishuCallbackEventEntity>()
                .eq(FeishuCallbackEventEntity::getEventId, eventId))).isEqualTo(1L);
    }

    @Test
    void anAnonymousCallbackStillAuthorizesThroughTheApproverBinding() throws Exception {
        // Production Feishu servers post this callback without a JWT. The approver binding alone
        // must carry the read authorization; otherwise the data-scope check inside the service
        // sees an anonymous user and rejects the click as cross-applicant access.
        String approverOpenId = "ou_anon_" + UUID.randomUUID().toString().substring(0, 8);
        bindApprover(approverOpenId, "user-anon", "匿名审批人");
        SubmittedOrder order = createSubmittedOrder("张三");

        String toast = postWithoutBearer("evt-anon-" + UUID.randomUUID(), approverOpenId,
                actionValue(order, "approve"));

        assertThat(toast).contains("已通过报销单");
        assertThat(statusOf(order.id())).isEqualTo("approved");
    }

    @Test
    void aSecondDecisionOnTheSameOrderIsAnsweredAsAConflictNotAnError() throws Exception {
        String firstApprover = "ou_first_" + UUID.randomUUID().toString().substring(0, 8);
        String secondApprover = "ou_second_" + UUID.randomUUID().toString().substring(0, 8);
        bindApprover(firstApprover, "user-first", "审批甲");
        bindApprover(secondApprover, "user-second", "审批乙");
        SubmittedOrder order = createSubmittedOrder("张三");

        assertThat(callbackBody(restTemplate.postForEntity(CALLBACK_URL,
                signed(callbackBody("evt-first-" + UUID.randomUUID(), firstApprover,
                        actionValue(order, "approve"), null), null),
                String.class)).get("toast").get("type").asText()).isEqualTo("success");

        // The second approver still holds a live card; clicking it must explain rather than 500.
        ResponseEntity<String> late = restTemplate.postForEntity(CALLBACK_URL,
                signed(callbackBody("evt-late-" + UUID.randomUUID(), secondApprover,
                        actionValue(order, "reject"), null), null),
                String.class);
        assertThat(late.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(callbackBody(late).get("toast").get("type").asText()).isEqualTo("error");
        assertThat(callbackBody(late).get("toast").get("content").asText()).contains("无需审批");
        assertThat(statusOf(order.id())).isEqualTo("approved");
    }

    @Test
    void approvalCardsSkipTheApplicantAndRequireAppModeForMemberListing() throws Exception {
        String applicantOpenId = "ou_applicant_" + UUID.randomUUID().toString().substring(0, 8);
        String colleagueOpenId = "ou_colleague_" + UUID.randomUUID().toString().substring(0, 8);
        // Both are approvers, but one of them is also the applicant of this order.
        bindApprover(applicantOpenId, "user-sole", "申请人本人");
        bindApprover(colleagueOpenId, "user-colleague", "同事审批人");
        createSubmittedOrder("申请人本人");

        // The applicant must never hold an actionable card for their own order, while the colleague does.
        assertThat(countOutboxByTarget(applicantOpenId)).isZero();
        assertThat(countOutboxByTarget(colleagueOpenId)).isPositive();

        // Listing group members needs the app bot client, which is absent in this profile.
        ResponseEntity<String> members = restTemplate.getForEntity("/api/feishu/chat-members", String.class);
        assertThat(members.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(members.getBody()).contains("FEISHU_APP_MODE_REQUIRED");
    }

    /**
     * Counts outbox rows addressed to one recipient. The open_id only appears in the target column,
     * so this isolates the approval cards sent to that specific person.
     */
    private int countOutboxByTarget(String openId) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/feishu/notifications?current=1&size=50&keyword=" + openId, String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return data(response).get("total").asInt();
    }

    // ---------- callback helpers ----------

    private String postAction(String eventId, String openId, Map<String, Object> actionValue,
                              Map<String, Object> formValue) throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(
                CALLBACK_URL, signed(callbackBody(eventId, openId, actionValue, formValue), null), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return callbackBody(response).get("toast").get("content").asText();
    }

    /** Posts a callback the way Feishu does: no Authorization header at all. */
    private String postWithoutBearer(String eventId, String openId, Map<String, Object> actionValue) throws Exception {
        restTemplate.getRestTemplate().getInterceptors().clear();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    CALLBACK_URL, signed(callbackBody(eventId, openId, actionValue, null), null), String.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            return callbackBody(response).get("toast").get("content").asText();
        } finally {
            TestAuth.applyBearer(restTemplate, TestAuth.adminToken(jwtTokenService));
        }
    }

    private Map<String, Object> actionValue(SubmittedOrder order, String action) {
        long submittedAt = submittedAtEpochSecond(order);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("action", action);
        value.put("reimbursementId", String.valueOf(order.id()));
        value.put("submittedAt", String.valueOf(submittedAt));
        value.put("actionToken", cardActionTokenSupport.sign(order.id(), action, submittedAt));
        return value;
    }

    private long submittedAtEpochSecond(SubmittedOrder order) {
        return OffsetDateTime.parse(order.submittedAt()).toEpochSecond();
    }

    /**
     * Builds a card action callback in the schema 2.0 shape Feishu sends.
     */
    private String callbackBody(String eventId, String openId, Map<String, Object> actionValue,
                                Map<String, Object> formValue) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("event_id", eventId);
        header.put("token", VERIFICATION_TOKEN);
        header.put("create_time", String.valueOf(System.currentTimeMillis()));
        header.put("event_type", EVENT_TYPE);
        header.put("tenant_key", "test_tenant");
        header.put("app_id", "cli_test_app");

        Map<String, Object> operator = new LinkedHashMap<>();
        operator.put("tenant_key", "test_tenant");
        operator.put("open_id", openId);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("value", actionValue);
        action.put("tag", "button");
        action.put("name", "approve_button");
        if (formValue != null) {
            action.put("form_value", formValue);
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("open_message_id", "om_test_message");
        context.put("open_chat_id", "oc_test_chat");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("operator", operator);
        event.put("token", "c-test-card-update-token");
        event.put("action", action);
        event.put("host", "im_message");
        event.put("context", context);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "2.0");
        root.put("header", header);
        root.put("event", event);
        return objectMapper.writeValueAsString(root);
    }

    /**
     * Signs the way Feishu does: sha256(timestamp + nonce + encryptKey + rawBody), lowercase hex.
     */
    private HttpEntity<String> signed(String body, String signatureOverride) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signature = signatureOverride != null
                ? signatureOverride
                : HashUtils.sha256Hex(timestamp + nonce + ENCRYPT_KEY + body);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Lark-Request-Timestamp", timestamp);
        headers.set("X-Lark-Request-Nonce", nonce);
        headers.set("X-Lark-Signature", signature);
        return new HttpEntity<>(body, headers);
    }

    // ---------- fixtures ----------

    private long bindApprover(String openId, String userId, String userName) {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/feishu/approvers",
                jsonRequest(Map.of(
                        "openId", openId,
                        "userId", userId,
                        "userName", userName,
                        "tenantId", "test",
                        "role", "APPROVER")),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        try {
            return data(response).get("id").asLong();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read the bound approver id", exception);
        }
    }

    private void toggleApprover(long approverId) {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/feishu/approvers/" + approverId + "/status", jsonRequest(Map.of()), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private SubmittedOrder createSubmittedOrder(String applicant) throws Exception {
        long projectId = createProject();
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/reimbursements",
                jsonRequest(Map.of(
                        "projectId", projectId,
                        "applicant", applicant,
                        "paymentType", "reimbursement",
                        "items", List.of(Map.of(
                                "amount", new BigDecimal("120.00"),
                                "expenseDate", "2026-09-01",
                                "vendor", "Card Callback Vendor",
                                "invoiceNo", "INV-CARD-" + UUID.randomUUID().toString().substring(0, 8),
                                "description", "Card callback fixture expense")),
                        "reason", "Card callback fixture",
                        "submitNow", true)),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode order = data(response).get("order");
        assertThat(order.get("status").asText()).isEqualTo("submitted");
        return new SubmittedOrder(
                order.get("id").asLong(),
                order.get("reimbursementNo").asText(),
                order.get("submittedAt").asText());
    }

    private long createProject() throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/projects",
                jsonRequest(Map.of(
                        "projectCode", "CARD-" + UUID.randomUUID(),
                        "projectName", "Card Callback Project",
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

    private String statusOf(long reimbursementId) throws Exception {
        return orderNode(reimbursementId).get("status").asText();
    }

    private String rejectReasonOf(long reimbursementId) throws Exception {
        JsonNode reason = orderNode(reimbursementId).get("rejectReason");
        return reason == null || reason.isNull() ? null : reason.asText();
    }

    private JsonNode orderNode(long reimbursementId) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/reimbursements/" + reimbursementId, String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return data(response).get("order");
    }

    private FeishuCallbackEventEntity callbackEvent(String eventId) {
        return callbackEventMapper.selectOne(new LambdaQueryWrapper<FeishuCallbackEventEntity>()
                .eq(FeishuCallbackEventEntity::getEventId, eventId)
                .last("limit 1"));
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

    /**
     * The callback endpoint answers with the raw structure Feishu expects, not with the project's
     * ApiResponse envelope, so its body is parsed without unwrapping a data field.
     */
    private JsonNode callbackBody(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private record SubmittedOrder(long id, String reimbursementNo, String submittedAt) {
    }
}
