package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.aop.AuditOperation;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.common.util.HashUtils;
import com.rcdis.agent.config.FeishuProperties;
import com.rcdis.agent.dto.FeishuMessageResponse;
import com.rcdis.agent.dto.FeishuTestMessageRequest;
import com.rcdis.agent.dto.NotificationOutboxPageRequest;
import com.rcdis.agent.entity.NotificationOutboxEntity;
import com.rcdis.agent.infrastructure.feishu.CardActionTokenSupport;
import com.rcdis.agent.infrastructure.feishu.FeishuBotClient;
import com.rcdis.agent.mapper.NotificationOutboxMapper;
import com.rcdis.agent.service.FeishuApproverService;
import com.rcdis.agent.service.FeishuNotificationService;
import com.rcdis.agent.service.NotificationCardRenderer;
import com.rcdis.agent.service.NotificationTemplateSeeder;
import com.rcdis.agent.to.ApprovalDecisionTO;
import com.rcdis.agent.to.FeishuApproverTO;
import com.rcdis.agent.to.FeishuMessageTO;
import com.rcdis.agent.vo.FeishuConfigStatusVO;
import com.rcdis.agent.vo.NotificationOutboxVO;
import com.rcdis.agent.vo.ReimbursementDetailVO;
import com.rcdis.agent.vo.ReimbursementItemVO;
import com.rcdis.agent.vo.ReimbursementVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuNotificationServiceImpl implements FeishuNotificationService {

    private static final String APP_CLIENT_TYPE = "app";
    private static final String WEBHOOK_CLIENT_TYPE = "webhook";
    private static final String WEBHOOK_TARGET = "webhook";
    private static final String MESSAGE_TYPE_TEXT = "text";
    private static final String MESSAGE_TYPE_INTERACTIVE = "interactive";
    private static final String CHANNEL_FEISHU_APP = "FEISHU_APP";
    private static final String CHANNEL_FEISHU_WEBHOOK = "FEISHU_WEBHOOK";
    private static final String CHANNEL_FEISHU_NOOP = "FEISHU_NOOP";
    private static final String CONFIG_STATUS_DISABLED = "DISABLED";
    private static final String CONFIG_STATUS_READY = "READY";
    private static final String CONFIG_STATUS_INCOMPLETE = "INCOMPLETE";
    private static final String CONFIG_STATUS_INVALID = "INVALID";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_FAILED = "FAILED";
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final String RECEIVE_ID_TYPE_OPEN_ID = "open_id";
    private static final String ACTION_APPROVE = "approve";
    private static final String ACTION_REJECT = "reject";
    private static final int OPEN_ID_HASH_LENGTH = 12;

    private final FeishuBotClient feishuBotClient;
    private final FeishuProperties properties;
    private final NotificationOutboxMapper notificationOutboxMapper;
    private final ObjectMapper objectMapper;
    private final NotificationCardRenderer cardRenderer;
    private final FeishuApproverService feishuApproverService;
    private final CardActionTokenSupport cardActionTokenSupport;

    @Override
    public FeishuConfigStatusVO getConfigStatus() {
        String clientType = resolveConfiguredClientType();
        String channel = resolveChannelForStatus(clientType);
        boolean appIdConfigured = StringUtils.hasText(properties.getAppId());
        boolean appSecretConfigured = StringUtils.hasText(properties.getAppSecret());
        boolean defaultReceiveIdConfigured = StringUtils.hasText(properties.getDefaultReceiveId());
        boolean webhookConfigured = StringUtils.hasText(properties.getWebhookUrl());
        boolean signingSecretConfigured = StringUtils.hasText(properties.getSigningSecret());
        String status = resolveConfigStatus(
                clientType,
                appIdConfigured,
                appSecretConfigured,
                webhookConfigured);
        return new FeishuConfigStatusVO(
                properties.isEnabled(),
                clientType,
                channel,
                status,
                resolveConfigMessage(status, clientType, defaultReceiveIdConfigured),
                properties.getOpenApiBaseUrl(),
                appIdConfigured,
                appSecretConfigured,
                defaultReceiveIdConfigured,
                normalizeOptionalText(properties.getDefaultReceiveIdType()),
                mask(properties.getDefaultReceiveId()),
                webhookConfigured,
                signingSecretConfigured,
                properties.getMaxAttempts());
    }

    @Override
    public PageResponse<NotificationOutboxVO> pageNotifications(NotificationOutboxPageRequest request) {
        validatePage(request.current(), request.size());
        String status = normalizeOptionalText(request.status());
        String channel = normalizeOptionalText(request.channel());
        String keyword = normalizeOptionalText(request.keyword());

        Page<NotificationOutboxEntity> page = new Page<>(request.current(), request.size());
        LambdaQueryWrapper<NotificationOutboxEntity> wrapper = new LambdaQueryWrapper<NotificationOutboxEntity>()
                .eq(StringUtils.hasText(status), NotificationOutboxEntity::getStatus, status)
                .eq(StringUtils.hasText(channel), NotificationOutboxEntity::getChannel, channel)
                .and(StringUtils.hasText(keyword), condition -> condition
                        .like(NotificationOutboxEntity::getTarget, keyword)
                        .or()
                        .like(NotificationOutboxEntity::getIdempotencyKey, keyword)
                        .or()
                        .like(NotificationOutboxEntity::getPayload, keyword)
                        .or()
                        .like(NotificationOutboxEntity::getErrorMessage, keyword))
                .orderByDesc(NotificationOutboxEntity::getCreatedAt)
                .orderByDesc(NotificationOutboxEntity::getId);
        Page<NotificationOutboxEntity> entityPage = notificationOutboxMapper.selectPage(page, wrapper);
        Page<NotificationOutboxVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(NotificationOutboxVO::fromEntity)
                .toList());
        return PageResponse.fromPage(voPage);
    }

    @Override
    @AuditOperation(action = "SEND_TEST_FEISHU_MESSAGE", targetType = "FEISHU_BOT")
    public FeishuMessageResponse sendTestMessage(FeishuTestMessageRequest request, CurrentUserTO currentUser) {
        String channel = resolveOperationalChannel();
        String target = resolveTarget(request.target(), channel);
        String receiveIdType = resolveReceiveIdType(request.receiveIdType(), channel);
        String idempotencyKey = resolveIdempotencyKey(request.idempotencyKey());
        Map<String, Object> payload = buildTextPayload(request.text(), receiveIdType, channel);
        String payloadJson = writePayload(payload);
        PendingNotification pending = insertPendingNotification(
                channel,
                target,
                MESSAGE_TYPE_TEXT,
                payloadJson,
                idempotencyKey);

        if (!pending.inserted()) {
            log.atInfo()
                    .addKeyValue("notificationId", pending.entity().getId())
                    .addKeyValue("userId", currentUser.userId())
                    .addKeyValue("tenantId", currentUser.tenantId())
                    .addKeyValue("idempotencyKey", idempotencyKey)
                    .log("Duplicate Feishu notification request ignored");
            return toMessageResponse(pending.entity(), true);
        }

        FeishuMessageTO message = new FeishuMessageTO(
                MESSAGE_TYPE_TEXT,
                target,
                payload,
                idempotencyKey);
        try {
            feishuBotClient.sendMessage(message);
        } catch (RuntimeException exception) {
            markAsFailed(pending.entity(), exception);
            throw exception;
        }

        NotificationOutboxEntity sent = markAsSent(pending.entity());
        log.atInfo()
                .addKeyValue("notificationId", sent.getId())
                .addKeyValue("userId", currentUser.userId())
                .addKeyValue("tenantId", currentUser.tenantId())
                .addKeyValue("channel", channel)
                .addKeyValue("target", target)
                .addKeyValue("idempotencyKey", idempotencyKey)
                .log("Feishu test notification submitted");
        return toMessageResponse(sent, false);
    }

    @Override
    public FeishuMessageResponse notifyReimbursementSubmitted(ReimbursementDetailVO detail) {
        ReimbursementVO order = detail.order();
        String idempotencyKey = "reimbursement-submit-" + order.id()
                + "-" + epochSecondOrZero(order.submittedAt());

        Map<String, String> context = baseReimbursementContext(detail);
        context.put("proofSummary", proofSummary(detail.items()));
        context.put("submittedAt", formatDateTime(order.submittedAt()));
        return sendCardNotification(
                NotificationTemplateSeeder.CODE_REIMBURSEMENT_SUBMITTED,
                idempotencyKey,
                context,
                order.id());
    }

    @Override
    public FeishuMessageResponse notifyReimbursementApproved(ReimbursementDetailVO detail) {
        ReimbursementVO order = detail.order();
        String idempotencyKey = "reimbursement-approve-" + order.id()
                + "-" + epochSecondOrZero(order.approvedAt());

        Map<String, String> context = baseReimbursementContext(detail);
        context.put("approvedAt", formatDateTime(order.approvedAt()));
        return sendCardNotification(
                NotificationTemplateSeeder.CODE_REIMBURSEMENT_APPROVED,
                idempotencyKey,
                context,
                order.id());
    }

    @Override
    public FeishuMessageResponse notifyReimbursementRejected(ReimbursementDetailVO detail) {
        ReimbursementVO order = detail.order();
        // Rejection has no dedicated timestamp column; use the send time so that
        // rejecting a resubmitted order produces a fresh notification.
        String idempotencyKey = "reimbursement-reject-" + order.id()
                + "-" + OffsetDateTime.now().toEpochSecond();

        Map<String, String> context = baseReimbursementContext(detail);
        context.put("rejectReason", fallbackText(order.rejectReason(), "--"));
        return sendCardNotification(
                NotificationTemplateSeeder.CODE_REIMBURSEMENT_REJECTED,
                idempotencyKey,
                context,
                order.id());
    }

    /**
     * Renders the card template, records the outbox entry and sends it through the bot client.
     */
    private FeishuMessageResponse sendCardNotification(
            String templateCode,
            String idempotencyKey,
            Map<String, String> context,
            Long reimbursementId
    ) {
        return sendCardNotification(templateCode, idempotencyKey, context, reimbursementId, null, null);
    }

    /**
     * @param targetOverride        explicit recipient, used to private-message one approver
     * @param receiveIdTypeOverride recipient id type matching the override, for example {@code open_id}
     */
    private FeishuMessageResponse sendCardNotification(
            String templateCode,
            String idempotencyKey,
            Map<String, String> context,
            Long reimbursementId,
            String targetOverride,
            String receiveIdTypeOverride
    ) {
        String channel = resolveOperationalChannel();
        String target = StringUtils.hasText(targetOverride) ? targetOverride.trim() : resolveTarget(null, channel);
        String receiveIdType = StringUtils.hasText(receiveIdTypeOverride)
                ? receiveIdTypeOverride.trim()
                : resolveReceiveIdType(null, channel);

        Map<String, Object> card = cardRenderer.render(templateCode, context);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("card", card);
        payload.put("channel", channel);
        if (StringUtils.hasText(receiveIdType)) {
            payload.put("receiveIdType", receiveIdType);
        }
        String payloadJson = writePayload(payload);

        PendingNotification pending = insertPendingNotification(
                channel,
                target,
                MESSAGE_TYPE_INTERACTIVE,
                payloadJson,
                idempotencyKey);
        if (!pending.inserted()) {
            log.atInfo()
                    .addKeyValue("notificationId", pending.entity().getId())
                    .addKeyValue("reimbursementId", reimbursementId)
                    .addKeyValue("idempotencyKey", idempotencyKey)
                    .log("Duplicate reimbursement notification ignored");
            return toMessageResponse(pending.entity(), true);
        }

        FeishuMessageTO message = new FeishuMessageTO(
                MESSAGE_TYPE_INTERACTIVE,
                target,
                payload,
                idempotencyKey);
        try {
            feishuBotClient.sendMessage(message);
        } catch (RuntimeException exception) {
            markAsFailed(pending.entity(), exception);
            throw exception;
        }

        NotificationOutboxEntity sent = markAsSent(pending.entity());
        log.atInfo()
                .addKeyValue("notificationId", sent.getId())
                .addKeyValue("reimbursementId", reimbursementId)
                .addKeyValue("templateCode", templateCode)
                .addKeyValue("channel", channel)
                .addKeyValue("idempotencyKey", idempotencyKey)
                .log("Reimbursement card notification sent");
        return toMessageResponse(sent, false);
    }

    @Override
    public List<FeishuMessageResponse> notifyReimbursementApprovalRequested(ReimbursementDetailVO detail) {
        ReimbursementVO order = detail.order();
        if (!properties.isApprovalCardEnabled()) {
            log.atInfo()
                    .addKeyValue("reimbursementId", order.id())
                    .log("Interactive approval card push is disabled");
            return List.of();
        }
        List<FeishuApproverTO> approvers = feishuApproverService.listActiveApprovers();
        if (approvers.isEmpty()) {
            log.atWarn()
                    .addKeyValue("reimbursementId", order.id())
                    .log("No active Feishu approver is bound, so no interactive approval card was sent");
            return List.of();
        }

        long submittedAtEpochSecond = epochSecondOrZero(order.submittedAt());
        Map<String, String> context = approvalCardContext(detail, submittedAtEpochSecond);
        List<FeishuMessageResponse> responses = new ArrayList<>();
        for (FeishuApproverTO approver : approvers) {
            if (isSamePerson(order.applicant(), approver.userName())) {
                // An applicant must never hold an actionable card for their own order.
                log.atInfo()
                        .addKeyValue("reimbursementId", order.id())
                        .addKeyValue("approverUserId", approver.userId())
                        .log("Skipped the approval card because the approver is the applicant");
                continue;
            }
            // The open_id is hashed so that the idempotency key stays stable but is not an identifier.
            String idempotencyKey = "reimbursement-approval-" + order.id()
                    + "-" + submittedAtEpochSecond
                    + "-" + openIdFingerprint(approver.openId());
            responses.add(sendCardNotification(
                    NotificationTemplateSeeder.CODE_REIMBURSEMENT_APPROVAL,
                    idempotencyKey,
                    context,
                    order.id(),
                    approver.openId(),
                    RECEIVE_ID_TYPE_OPEN_ID));
        }
        log.atInfo()
                .addKeyValue("reimbursementId", order.id())
                .addKeyValue("approverCount", approvers.size())
                .addKeyValue("cardCount", responses.size())
                .log("Reimbursement approval cards dispatched");
        return List.copyOf(responses);
    }

    @Override
    public Map<String, Object> renderApprovalDecisionCard(
            ReimbursementDetailVO detail,
            ApprovalDecisionTO decision
    ) {
        Map<String, String> context = baseReimbursementContext(detail);
        context.put("headerColor", decision.approved() ? "green" : "red");
        context.put("decisionTitle", decision.approved() ? "报销单已通过" : "报销单已驳回");
        context.put("decidedBy", fallbackText(decision.decidedBy(), "--"));
        context.put("decidedAt", formatDateTime(decision.decidedAt()));
        context.put("decisionDetailLabel", decision.approved() ? "审批意见" : "驳回原因");
        context.put("decisionDetail",
                fallbackText(decision.rejectReason(), decision.approved() ? "无" : "--"));
        return cardRenderer.render(NotificationTemplateSeeder.CODE_REIMBURSEMENT_APPROVAL_DECIDED, context);
    }

    /**
     * Builds the approval card context, including the signed action tokens that let the callback
     * reject a tampered reimbursement id or a flipped action.
     */
    private Map<String, String> approvalCardContext(ReimbursementDetailVO detail, long submittedAtEpochSecond) {
        ReimbursementVO order = detail.order();
        Map<String, String> context = baseReimbursementContext(detail);
        context.put("proofSummary", proofSummary(detail.items()));
        context.put("submittedAt", formatDateTime(order.submittedAt()));
        context.put("reimbursementId", String.valueOf(order.id()));
        context.put("submittedAtEpochSecond", String.valueOf(submittedAtEpochSecond));
        context.put("approveActionToken",
                cardActionTokenSupport.sign(order.id(), ACTION_APPROVE, submittedAtEpochSecond));
        context.put("rejectActionToken",
                cardActionTokenSupport.sign(order.id(), ACTION_REJECT, submittedAtEpochSecond));
        return context;
    }

    private boolean isSamePerson(String applicant, String approverName) {
        if (!StringUtils.hasText(applicant) || !StringUtils.hasText(approverName)) {
            return false;
        }
        return applicant.trim().equalsIgnoreCase(approverName.trim());
    }

    private String openIdFingerprint(String openId) {
        String hash = HashUtils.sha256Hex(openId == null ? "" : openId);
        return hash.substring(0, Math.min(OPEN_ID_HASH_LENGTH, hash.length()));
    }

    private Map<String, String> baseReimbursementContext(ReimbursementDetailVO detail) {
        ReimbursementVO order = detail.order();
        String projectLabel = StringUtils.hasText(order.projectName())
                ? order.projectName()
                : order.projectCode();
        Map<String, String> context = new LinkedHashMap<>();
        context.put("applicant", fallbackText(order.applicant(), "--"));
        context.put("projectName", fallbackText(projectLabel, "--"));
        context.put("totalAmountText", "¥" + order.totalAmount() + "（" + order.itemCount() + " 张单据）");
        context.put("reimbursementNo", fallbackText(order.reimbursementNo(), "--"));
        return context;
    }

    private String resolveConfiguredClientType() {
        if (StringUtils.hasText(properties.getClientType())) {
            return properties.getClientType().trim().toLowerCase(Locale.ROOT);
        }
        return WEBHOOK_CLIENT_TYPE;
    }

    private String resolveChannelForStatus(String clientType) {
        if (!properties.isEnabled()) {
            return CHANNEL_FEISHU_NOOP;
        }
        if (APP_CLIENT_TYPE.equals(clientType)) {
            return CHANNEL_FEISHU_APP;
        }
        if (WEBHOOK_CLIENT_TYPE.equals(clientType)) {
            return CHANNEL_FEISHU_WEBHOOK;
        }
        return CONFIG_STATUS_INVALID;
    }

    private String resolveOperationalChannel() {
        if (!properties.isEnabled()) {
            return CHANNEL_FEISHU_NOOP;
        }
        String clientType = resolveConfiguredClientType();
        if (APP_CLIENT_TYPE.equals(clientType)) {
            return CHANNEL_FEISHU_APP;
        }
        if (WEBHOOK_CLIENT_TYPE.equals(clientType)) {
            return CHANNEL_FEISHU_WEBHOOK;
        }
        throw new BusinessException(
                "FEISHU_CLIENT_TYPE_INVALID",
                "Feishu client type must be app or webhook. clientType=" + clientType);
    }

    private String resolveConfigStatus(
            String clientType,
            boolean appIdConfigured,
            boolean appSecretConfigured,
            boolean webhookConfigured
    ) {
        if (!properties.isEnabled()) {
            return CONFIG_STATUS_DISABLED;
        }
        if (properties.getMaxAttempts() < 1) {
            return CONFIG_STATUS_INCOMPLETE;
        }
        if (APP_CLIENT_TYPE.equals(clientType)) {
            if (StringUtils.hasText(properties.getOpenApiBaseUrl())
                    && appIdConfigured
                    && appSecretConfigured
                    && StringUtils.hasText(properties.getDefaultReceiveIdType())) {
                return CONFIG_STATUS_READY;
            }
            return CONFIG_STATUS_INCOMPLETE;
        }
        if (WEBHOOK_CLIENT_TYPE.equals(clientType)) {
            return webhookConfigured ? CONFIG_STATUS_READY : CONFIG_STATUS_INCOMPLETE;
        }
        return CONFIG_STATUS_INVALID;
    }

    private String resolveConfigMessage(String status, String clientType, boolean defaultReceiveIdConfigured) {
        if (CONFIG_STATUS_DISABLED.equals(status)) {
            return "飞书通知未启用，当前请求会进入本地 Noop 链路。";
        }
        if (CONFIG_STATUS_INVALID.equals(status)) {
            return "飞书 client-type 配置无效，仅支持 app 或 webhook。";
        }
        if (CONFIG_STATUS_INCOMPLETE.equals(status)) {
            return "飞书通知配置不完整，请检查服务端环境变量。";
        }
        if (APP_CLIENT_TYPE.equals(clientType) && !defaultReceiveIdConfigured) {
            return "自建应用机器人已可用，未配置默认接收方，发送时需要手动填写接收方 ID。";
        }
        return "飞书通知配置可用。";
    }

    private String resolveTarget(String target, String channel) {
        if (CHANNEL_FEISHU_APP.equals(channel)) {
            return resolveAppTarget(target);
        }
        if (CHANNEL_FEISHU_NOOP.equals(channel) && APP_CLIENT_TYPE.equals(resolveConfiguredClientType())) {
            if (StringUtils.hasText(target) && !WEBHOOK_TARGET.equalsIgnoreCase(target.trim())) {
                return target.trim();
            }
            if (StringUtils.hasText(properties.getDefaultReceiveId())) {
                return properties.getDefaultReceiveId().trim();
            }
        }
        return WEBHOOK_TARGET;
    }

    private String resolveAppTarget(String target) {
        if (StringUtils.hasText(target) && !WEBHOOK_TARGET.equalsIgnoreCase(target.trim())) {
            return target.trim();
        }
        if (StringUtils.hasText(properties.getDefaultReceiveId())) {
            return properties.getDefaultReceiveId().trim();
        }
        throw new BusinessException(
                "FEISHU_RECEIVE_ID_REQUIRED",
                "Feishu app bot message requires target or rcdis.feishu.default-receive-id");
    }

    private String resolveReceiveIdType(String receiveIdType, String channel) {
        if (StringUtils.hasText(receiveIdType)) {
            return receiveIdType.trim();
        }
        if (CHANNEL_FEISHU_APP.equals(channel) || CHANNEL_FEISHU_NOOP.equals(channel)) {
            if (StringUtils.hasText(properties.getDefaultReceiveIdType())) {
                return properties.getDefaultReceiveIdType().trim();
            }
            if (CHANNEL_FEISHU_APP.equals(channel)) {
                throw new BusinessException(
                        "FEISHU_RECEIVE_ID_TYPE_REQUIRED",
                        "Feishu app bot message requires receiveIdType or rcdis.feishu.default-receive-id-type");
            }
        }
        return null;
    }

    private String resolveIdempotencyKey(String idempotencyKey) {
        if (StringUtils.hasText(idempotencyKey)) {
            return idempotencyKey.trim();
        }
        return UUID.randomUUID().toString();
    }

    private Map<String, Object> buildTextPayload(String text, String receiveIdType, String channel) {
        String normalizedText = normalizeRequiredText(text, "text");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", normalizedText);
        payload.put("channel", channel);
        if (StringUtils.hasText(receiveIdType)) {
            payload.put("receiveIdType", receiveIdType);
        }
        return payload;
    }

    private String proofSummary(List<ReimbursementItemVO> items) {
        if (items.isEmpty()) {
            return "无关联单据";
        }
        long withProof = items.stream()
                .filter(item -> StringUtils.hasText(item.invoiceNo())
                        || StringUtils.hasText(item.receiptFile()))
                .count();
        if (withProof == items.size()) {
            return "有（" + withProof + "/" + items.size() + "）";
        }
        return "部分缺失（有 " + withProof + "/" + items.size() + "）";
    }

    private String formatDateTime(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return "--";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(dateTime.atZoneSameInstant(ZoneId.systemDefault()));
    }

    private long epochSecondOrZero(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return 0L;
        }
        return dateTime.toEpochSecond();
    }

    private String fallbackText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private PendingNotification insertPendingNotification(
            String channel,
            String target,
            String messageType,
            String payload,
            String idempotencyKey
    ) {
        NotificationOutboxEntity existing = findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return new PendingNotification(existing, false);
        }

        OffsetDateTime now = OffsetDateTime.now();
        NotificationOutboxEntity entity = new NotificationOutboxEntity();
        entity.setChannel(channel);
        entity.setTarget(target);
        entity.setMessageType(messageType);
        entity.setPayload(payload);
        entity.setStatus(STATUS_PENDING);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            notificationOutboxMapper.insert(entity);
            return new PendingNotification(entity, true);
        } catch (DuplicateKeyException exception) {
            NotificationOutboxEntity duplicate = findByIdempotencyKey(idempotencyKey);
            if (duplicate == null) {
                throw new BusinessException(
                        "NOTIFICATION_IDEMPOTENCY_CONFLICT",
                        "Notification idempotency key already exists but record cannot be loaded. idempotencyKey="
                                + idempotencyKey,
                        HttpStatus.CONFLICT,
                        exception);
            }
            return new PendingNotification(duplicate, false);
        }
    }

    private NotificationOutboxEntity findByIdempotencyKey(String idempotencyKey) {
        return notificationOutboxMapper.selectOne(new LambdaQueryWrapper<NotificationOutboxEntity>()
                .eq(NotificationOutboxEntity::getIdempotencyKey, idempotencyKey));
    }

    private NotificationOutboxEntity markAsSent(NotificationOutboxEntity entity) {
        OffsetDateTime now = OffsetDateTime.now();
        NotificationOutboxEntity update = new NotificationOutboxEntity();
        update.setId(entity.getId());
        update.setStatus(STATUS_SENT);
        update.setSentAt(now);
        update.setUpdatedAt(now);
        int updated = notificationOutboxMapper.updateById(update);
        if (updated != 1) {
            throw new BusinessException(
                    "NOTIFICATION_OUTBOX_UPDATE_FAILED",
                    "Failed to mark Feishu notification as sent. notificationId=" + entity.getId());
        }
        return notificationOutboxMapper.selectById(entity.getId());
    }

    private void markAsFailed(NotificationOutboxEntity entity, RuntimeException failure) {
        OffsetDateTime now = OffsetDateTime.now();
        NotificationOutboxEntity update = new NotificationOutboxEntity();
        update.setId(entity.getId());
        update.setStatus(STATUS_FAILED);
        update.setErrorMessage(sanitizeErrorMessage(failure));
        update.setUpdatedAt(now);
        int updated = notificationOutboxMapper.updateById(update);
        if (updated != 1) {
            throw new BusinessException(
                    "NOTIFICATION_OUTBOX_UPDATE_FAILED",
                    "Failed to mark Feishu notification as failed. notificationId=" + entity.getId(),
                    failure);
        }
    }

    private FeishuMessageResponse toMessageResponse(NotificationOutboxEntity entity, boolean duplicate) {
        return new FeishuMessageResponse(
                entity.getId(),
                entity.getIdempotencyKey(),
                entity.getMessageType(),
                entity.getTarget(),
                entity.getStatus(),
                duplicate,
                entity.getSentAt());
    }

    private void validatePage(long current, long size) {
        if (current < 1) {
            throw new BusinessException("PAGE_CURRENT_INVALID", "Page current must be greater than 0");
        }
        if (size < 1 || size > 500) {
            throw new BusinessException("PAGE_SIZE_INVALID", "Page size must be between 1 and 500");
        }
    }

    private String normalizeRequiredText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("TEXT_REQUIRED", fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String normalizeOptionalText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "NOTIFICATION_PAYLOAD_SERIALIZE_FAILED",
                    "Failed to serialize notification payload",
                    exception);
        }
    }

    private String sanitizeErrorMessage(RuntimeException failure) {
        String message = failure.getMessage();
        if (!StringUtils.hasText(message)) {
            message = failure.getClass().getSimpleName();
        }
        String trimmed = message.trim();
        if (trimmed.length() <= MAX_ERROR_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_ERROR_LENGTH);
    }

    private String mask(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    private record PendingNotification(
            NotificationOutboxEntity entity,
            boolean inserted
    ) {
    }
}
