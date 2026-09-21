package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.dto.ReimbursementActionRequest;
import com.rcdis.agent.entity.FeishuCallbackEventEntity;
import com.rcdis.agent.infrastructure.feishu.CardActionTokenSupport;
import com.rcdis.agent.mapper.FeishuCallbackEventMapper;
import com.rcdis.agent.service.FeishuApproverService;
import com.rcdis.agent.service.FeishuCardActionService;
import com.rcdis.agent.service.FeishuNotificationService;
import com.rcdis.agent.service.NotificationTemplateSeeder;
import com.rcdis.agent.service.ReimbursementApprovalService;
import com.rcdis.agent.service.ReimbursementService;
import com.rcdis.agent.to.ApprovalDecisionTO;
import com.rcdis.agent.to.CardActionOutcomeTO;
import com.rcdis.agent.to.CardActionRequestTO;
import com.rcdis.agent.to.FeishuApproverTO;
import com.rcdis.agent.vo.ReimbursementDetailVO;
import com.rcdis.agent.vo.ReimbursementVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes a reimbursement decision triggered by clicking a Feishu approval card button.
 *
 * <p>Four guards run before anything is written, in this order:</p>
 * <ol>
 *   <li><b>Idempotency</b> — Feishu retries a callback that is not answered within 3 seconds, so the
 *       event id is recorded first and a repeat is answered without executing again.</li>
 *   <li><b>Tamper check</b> — the reimbursement id, action and submission time carried by the button
 *       must match the HMAC that was embedded when the card was rendered. Feishu proves the callback
 *       came from Feishu, not that the button value is untouched.</li>
 *   <li><b>Authorization</b> — the clicker's open_id must map to an active approver binding. A card
 *       cannot hide its buttons per recipient, so this check is the only thing standing between a
 *       group member and a financial state change.</li>
 *   <li><b>Self-approval</b> — the approver must not be the applicant of this order.</li>
 * </ol>
 *
 * <p>Business rejections become toasts rather than exceptions so the approver sees the reason inside
 * Feishu, and the replacement card is returned in the callback response, which updates the card in
 * place without consuming the 30 minute / two update card token.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuCardActionServiceImpl implements FeishuCardActionService {

    private static final String EVENT_TYPE_CARD_ACTION = "card.action.trigger";
    private static final String ACTION_APPROVE = "approve";
    private static final String ACTION_REJECT = "reject";
    private static final String FIELD_ACTION = "action";
    private static final String FIELD_REIMBURSEMENT_ID = "reimbursementId";
    private static final String FIELD_SUBMITTED_AT = "submittedAt";
    private static final String FIELD_ACTION_TOKEN = "actionToken";
    private static final String ORDER_STATUS_SUBMITTED = "submitted";
    private static final String STATUS_CONFLICT_CODE = "REIMBURSEMENT_STATUS_CONFLICT";
    private static final int MAX_PAYLOAD_LENGTH = 8000;
    private static final int MAX_REASON_LENGTH = 500;

    private final FeishuCallbackEventMapper callbackEventMapper;
    private final FeishuApproverService feishuApproverService;
    private final ReimbursementService reimbursementService;
    private final ReimbursementApprovalService reimbursementApprovalService;
    private final FeishuNotificationService feishuNotificationService;
    private final CardActionTokenSupport cardActionTokenSupport;

    @Override
    public CardActionOutcomeTO handleCardAction(CardActionRequestTO request) {
        String idempotencyKey = resolveIdempotencyKey(request);
        if (!recordCallback(request, idempotencyKey)) {
            log.atInfo()
                    .addKeyValue("eventId", idempotencyKey)
                    .log("Duplicate Feishu card callback ignored");
            return CardActionOutcomeTO.duplicate("该操作此前已处理，未重复执行");
        }

        CardActionOutcomeTO outcome;
        try {
            outcome = decide(request);
        } catch (RuntimeException exception) {
            log.atError()
                    .setCause(exception)
                    .addKeyValue("eventId", idempotencyKey)
                    .log("Feishu card action failed unexpectedly");
            outcome = CardActionOutcomeTO.toast(
                    CardActionOutcomeTO.TOAST_ERROR,
                    "处理失败，请到报销中心查看该单据状态后再操作");
        }
        markProcessed(idempotencyKey);
        return outcome;
    }

    private CardActionOutcomeTO decide(CardActionRequestTO request) {
        AuthorizedAction authorized;
        try {
            authorized = parseAndAuthorize(request);
        } catch (BusinessException exception) {
            log.atWarn()
                    .setCause(exception)
                    .addKeyValue("actionValue", request.actionValue())
                    .log("Feishu card action rejected before execution");
            return CardActionOutcomeTO.toast(CardActionOutcomeTO.TOAST_ERROR, exception.getMessage());
        }

        ReimbursementVO order = authorized.detail().order();
        boolean approve = ACTION_APPROVE.equals(authorized.action());
        String rejectReason = approve ? null : resolveRejectReason(request.formValue());

        CurrentUserTO previousUser = CurrentUserContextHolder.currentOrNull();
        // Run as the approver so that the audit entry and updated_by name the person who clicked,
        // not an anonymous callback thread.
        CurrentUserContextHolder.set(CurrentUserTO.of(
                authorized.approver().userId(),
                authorized.approver().userName(),
                authorized.approver().tenantId(),
                null,
                StringUtils.hasText(authorized.approver().role())
                        ? Set.of(authorized.approver().role())
                        : Set.of()));
        try {
            ReimbursementDetailVO decided = approve
                    ? reimbursementApprovalService.approveDeferredNotify(
                            order.id(), new ReimbursementActionRequest("飞书卡片审批通过"))
                    : reimbursementApprovalService.rejectDeferredNotify(
                            order.id(), new ReimbursementActionRequest(rejectReason));

            Map<String, Object> card = feishuNotificationService.renderApprovalDecisionCard(
                    decided,
                    new ApprovalDecisionTO(
                            approve,
                            authorized.approver().userName(),
                            OffsetDateTime.now(),
                            rejectReason));
            log.atInfo()
                    .addKeyValue("reimbursementId", order.id())
                    .addKeyValue("action", authorized.action())
                    .addKeyValue("approverUserId", authorized.approver().userId())
                    .log("Feishu card approval executed");
            return CardActionOutcomeTO.decided(
                    (approve ? "已通过报销单 " : "已驳回报销单 ") + fallback(order.reimbursementNo()),
                    card);
        } catch (BusinessException exception) {
            log.atWarn()
                    .setCause(exception)
                    .addKeyValue("reimbursementId", order.id())
                    .addKeyValue("action", authorized.action())
                    .log("Feishu card approval could not be executed");
            return CardActionOutcomeTO.toast(
                    CardActionOutcomeTO.TOAST_WARNING,
                    friendlyFailure(exception, order));
        } finally {
            if (previousUser != null) {
                CurrentUserContextHolder.set(previousUser);
            } else {
                CurrentUserContextHolder.clear();
            }
        }
    }

    private AuthorizedAction parseAndAuthorize(CardActionRequestTO request) {
        Map<String, Object> value = request.actionValue();
        if (value == null || value.isEmpty()) {
            throw new BusinessException(
                    "FEISHU_CARD_ACTION_VALUE_MISSING",
                    "卡片回传数据为空，请重新提交报销单以获取新的审批卡片。");
        }
        String action = stringValue(value, FIELD_ACTION);
        if (!ACTION_APPROVE.equals(action) && !ACTION_REJECT.equals(action)) {
            throw new BusinessException(
                    "FEISHU_CARD_ACTION_UNSUPPORTED",
                    "不支持的卡片操作：" + fallback(action));
        }
        Long reimbursementId = longValue(value, FIELD_REIMBURSEMENT_ID);
        if (reimbursementId == null) {
            throw new BusinessException(
                    "FEISHU_CARD_ACTION_VALUE_MISSING",
                    "卡片回传数据缺少报销单标识，请重新提交报销单以获取新的审批卡片。");
        }
        long submittedAt = longValueOrZero(value, FIELD_SUBMITTED_AT);
        if (!cardActionTokenSupport.verify(reimbursementId, action, submittedAt, stringValue(value, FIELD_ACTION_TOKEN))) {
            throw new BusinessException(
                    "FEISHU_CARD_ACTION_TOKEN_INVALID",
                    "操作凭证校验失败，卡片可能已过期或被修改。请到报销中心处理该单据。");
        }
        if (!StringUtils.hasText(request.openId())) {
            throw new BusinessException(
                    "FEISHU_CARD_OPERATOR_MISSING",
                    "回调未携带操作人身份，无法完成授权校验。");
        }

        FeishuApproverTO approver = feishuApproverService.findActiveByOpenId(request.openId())
                .orElseThrow(() -> new BusinessException(
                        "FEISHU_APPROVER_NOT_BOUND",
                        "你不在审批人名单中，无法审批报销单。请联系管理员在「飞书通知」页面绑定审批人。"));

        ReimbursementDetailVO detail = reimbursementService.getReimbursement(reimbursementId);
        ReimbursementVO order = detail.order();
        if (!ORDER_STATUS_SUBMITTED.equals(order.status())) {
            throw new BusinessException(
                    "REIMBURSEMENT_NOT_PENDING",
                    "报销单 " + fallback(order.reimbursementNo()) + " 当前状态为 " + order.status() + "，无需审批。");
        }
        if (isSamePerson(order.applicant(), approver.userName())) {
            throw new BusinessException(
                    "FEISHU_APPROVER_SELF_APPROVAL",
                    "不能审批本人提交的报销单（申请人：" + fallback(order.applicant()) + "）。");
        }
        return new AuthorizedAction(action, approver, detail);
    }

    /**
     * The card input is optional by design, so an empty reason falls back to a fixed meaningful text
     * rather than leaving the reject reason column and the audit trail blank.
     */
    private String resolveRejectReason(Map<String, Object> formValue) {
        String reason = extractReason(formValue);
        if (!StringUtils.hasText(reason)) {
            return ReimbursementApprovalService.DEFAULT_REJECT_REASON;
        }
        String trimmed = reason.trim();
        return trimmed.length() > MAX_REASON_LENGTH ? trimmed.substring(0, MAX_REASON_LENGTH) : trimmed;
    }

    private String extractReason(Map<String, Object> formValue) {
        if (formValue == null || formValue.isEmpty()) {
            return null;
        }
        Object named = formValue.get(NotificationTemplateSeeder.APPROVAL_REJECT_REASON_FIELD);
        if (named instanceof String text && StringUtils.hasText(text)) {
            return text;
        }
        // Tolerate a renamed form field in an edited template by accepting the only submitted value.
        if (named == null && formValue.size() == 1) {
            Object only = formValue.values().iterator().next();
            if (only instanceof String text && StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String friendlyFailure(BusinessException exception, ReimbursementVO order) {
        if (STATUS_CONFLICT_CODE.equals(exception.getCode())) {
            return "报销单 " + fallback(order.reimbursementNo()) + " 已被其他人处理，请刷新后查看最新状态。";
        }
        return exception.getMessage();
    }

    private String resolveIdempotencyKey(CardActionRequestTO request) {
        if (StringUtils.hasText(request.eventId())) {
            return request.eventId().trim();
        }
        // A payload without an event id must not be able to bypass the idempotency guard.
        return "card-action-" + UUID.randomUUID();
    }

    /**
     * @return true when this event was recorded for the first time and may be executed
     */
    private boolean recordCallback(CardActionRequestTO request, String idempotencyKey) {
        FeishuCallbackEventEntity existing = callbackEventMapper.selectOne(
                new LambdaQueryWrapper<FeishuCallbackEventEntity>()
                        .eq(FeishuCallbackEventEntity::getIdempotencyKey, idempotencyKey)
                        .last("limit 1"));
        if (existing != null) {
            return false;
        }
        FeishuCallbackEventEntity entity = new FeishuCallbackEventEntity();
        entity.setEventId(idempotencyKey);
        entity.setEventType(StringUtils.hasText(request.eventType()) ? request.eventType() : EVENT_TYPE_CARD_ACTION);
        entity.setSenderId(request.openId());
        entity.setChatId(request.openChatId());
        entity.setMessageId(request.openMessageId());
        entity.setPayload(truncatePayload(request.rawPayload()));
        entity.setIdempotencyKey(idempotencyKey);
        entity.setProcessed(Boolean.FALSE);
        entity.setCreatedAt(OffsetDateTime.now());
        try {
            callbackEventMapper.insert(entity);
            return true;
        } catch (DuplicateKeyException exception) {
            // A concurrent retry won the race; treat it as a duplicate rather than executing twice.
            return false;
        }
    }

    private void markProcessed(String idempotencyKey) {
        callbackEventMapper.update(null, new LambdaUpdateWrapper<FeishuCallbackEventEntity>()
                .eq(FeishuCallbackEventEntity::getIdempotencyKey, idempotencyKey)
                .set(FeishuCallbackEventEntity::getProcessed, Boolean.TRUE)
                .set(FeishuCallbackEventEntity::getProcessedAt, OffsetDateTime.now()));
    }

    private boolean isSamePerson(String applicant, String approverName) {
        if (!StringUtils.hasText(applicant) || !StringUtils.hasText(approverName)) {
            return false;
        }
        return applicant.trim().equalsIgnoreCase(approverName.trim());
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : text;
    }

    private static Long longValue(Map<String, Object> value, String field) {
        String text = stringValue(value, field);
        if (text == null) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static long longValueOrZero(Map<String, Object> value, String field) {
        Long parsed = longValue(value, field);
        return parsed == null ? 0L : parsed.longValue();
    }

    private static String truncatePayload(String payload) {
        if (!StringUtils.hasText(payload)) {
            return "{}";
        }
        String trimmed = payload.trim();
        return trimmed.length() <= MAX_PAYLOAD_LENGTH ? trimmed : trimmed.substring(0, MAX_PAYLOAD_LENGTH);
    }

    private static String fallback(String value) {
        return StringUtils.hasText(value) ? value.trim() : "--";
    }

    private record AuthorizedAction(
            String action,
            FeishuApproverTO approver,
            ReimbursementDetailVO detail
    ) {
    }
}
