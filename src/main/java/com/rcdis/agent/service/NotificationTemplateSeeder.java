package com.rcdis.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.entity.NotificationTemplateEntity;
import com.rcdis.agent.mapper.NotificationTemplateMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds builtin notification templates on startup.
 * Existing rows (including user edits) are never overwritten.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationTemplateSeeder implements ApplicationRunner {

    public static final String CODE_REIMBURSEMENT_SUBMITTED = "REIMBURSEMENT_SUBMITTED";
    public static final String CODE_REIMBURSEMENT_APPROVED = "REIMBURSEMENT_APPROVED";
    public static final String CODE_REIMBURSEMENT_REJECTED = "REIMBURSEMENT_REJECTED";
    /** Card JSON 2.0 with approve/reject buttons, private-messaged to bound approvers. */
    public static final String CODE_REIMBURSEMENT_APPROVAL = "REIMBURSEMENT_APPROVAL";
    /** Card JSON 2.0 returned by the callback to replace the approval card in place. */
    public static final String CODE_REIMBURSEMENT_APPROVAL_DECIDED = "REIMBURSEMENT_APPROVAL_DECIDED";

    /** Form and component names inside the approval card; the callback reads reject_reason from form_value. */
    public static final String APPROVAL_FORM_NAME = "approval_form";
    public static final String APPROVAL_REJECT_REASON_FIELD = "reject_reason";

    private static final String MESSAGE_TYPE_TEXT = "text";
    private static final String MESSAGE_TYPE_INTERACTIVE = "interactive";
    private static final String CARD_SCHEMA_V2 = "2.0";

    /**
     * Constructs that only exist in card JSON 1.0. Feishu rejects them inside a 2.0 card with error
     * 200861 at send time, which surfaces as a failed notification long after startup, so builtin
     * templates are checked here instead. {@code note} was replaced by the {@code markdown}
     * component, {@code action} by {@code behaviors} on each button, and {@code lark_md} by
     * {@code plain_text} or {@code markdown}.
     */
    private static final Set<String> CARD_V1_ONLY_TAGS = Set.of("note", "action", "lark_md");

    private static final String SUBMITTED_CARD = """
            {
              "config": { "wide_screen_mode": true },
              "header": {
                "template": "blue",
                "title": { "tag": "plain_text", "content": "报销单已提交，待审批" }
              },
              "elements": [
                {
                  "tag": "div",
                  "fields": [
                    { "is_short": true, "text": { "tag": "lark_md", "content": "**申请人**\\n{applicant}" } },
                    { "is_short": true, "text": { "tag": "lark_md", "content": "**项目**\\n{projectName}" } },
                    { "is_short": true, "text": { "tag": "lark_md", "content": "**报销金额**\\n{totalAmountText}" } },
                    { "is_short": true, "text": { "tag": "lark_md", "content": "**是否有发票/支付凭证**\\n{proofSummary}" } },
                    { "is_short": true, "text": { "tag": "lark_md", "content": "**提交时间**\\n{submittedAt}" } }
                  ]
                },
                { "tag": "hr" },
                {
                  "tag": "note",
                  "elements": [ { "tag": "plain_text", "content": "报销单 {reimbursementNo}，请及时审批。" } ]
                }
              ]
            }
            """;

    private static final String APPROVED_CARD = """
            {
              "config": { "wide_screen_mode": true },
              "header": {
                "template": "green",
                "title": { "tag": "plain_text", "content": "报销单已通过" }
              },
              "elements": [
                {
                  "tag": "div",
                  "fields": [
                    { "is_short": true, "text": { "tag": "lark_md", "content": "**申请人**\\n{applicant}" } },
                    { "is_short": true, "text": { "tag": "lark_md", "content": "**项目**\\n{projectName}" } },
                    { "is_short": true, "text": { "tag": "lark_md", "content": "**报销金额**\\n{totalAmountText}" } },
                    { "is_short": true, "text": { "tag": "lark_md", "content": "**通过时间**\\n{approvedAt}" } }
                  ]
                },
                { "tag": "hr" },
                {
                  "tag": "note",
                  "elements": [ { "tag": "plain_text", "content": "报销单 {reimbursementNo} 已通过审批。" } ]
                }
              ]
            }
            """;

    private static final String REJECTED_CARD = """
            {
              "config": { "wide_screen_mode": true },
              "header": {
                "template": "red",
                "title": { "tag": "plain_text", "content": "报销单已驳回" }
              },
              "elements": [
                {
                  "tag": "div",
                  "fields": [
                    { "is_short": true, "text": { "tag": "lark_md", "content": "**申请人**\\n{applicant}" } },
                    { "is_short": true, "text": { "tag": "lark_md", "content": "**项目**\\n{projectName}" } },
                    { "is_short": true, "text": { "tag": "lark_md", "content": "**报销金额**\\n{totalAmountText}" } },
                    { "is_short": true, "text": { "tag": "lark_md", "content": "**驳回原因**\\n{rejectReason}" } }
                  ]
                },
                { "tag": "hr" },
                {
                  "tag": "note",
                  "elements": [ { "tag": "plain_text", "content": "报销单 {reimbursementNo} 被驳回，请修改后重新提交。" } ]
                }
              ]
            }
            """;

    /**
     * Approval card in card JSON 2.0. Buttons must use {@code behaviors:[{type:"callback"}]} so that
     * the new {@code card.action.trigger} callback fires, which is the only variant the WebSocket
     * long connection supports. The reject reason input and both buttons live in one form container
     * so a single click carries {@code action.value} and {@code action.form_value} together.
     *
     * <p>Only components documented for 2.0 are used: {@code markdown} for text and {@code form} /
     * {@code input} / {@code button} for interaction. The 1.0 constructs {@code note}, {@code div}
     * with {@code fields} and {@code lark_md} are rejected by Feishu with error 200861.</p>
     */
    private static final String APPROVAL_CARD = """
            {
              "schema": "2.0",
              "config": { "update_multi": true },
              "header": {
                "template": "blue",
                "title": { "tag": "plain_text", "content": "报销单待审批" },
                "subtitle": { "tag": "plain_text", "content": "{reimbursementNo}" }
              },
              "body": {
                "direction": "vertical",
                "elements": [
                  {
                    "tag": "markdown",
                    "content": "**申请人**：{applicant}\\n**项目**：{projectName}\\n**报销金额**：{totalAmountText}\\n**发票/支付凭证**：{proofSummary}\\n**提交时间**：{submittedAt}"
                  },
                  {
                    "tag": "form",
                    "name": "approval_form",
                    "direction": "vertical",
                    "elements": [
                      {
                        "tag": "input",
                        "name": "reject_reason",
                        "required": false,
                        "input_type": "multiline_text",
                        "rows": 2,
                        "max_length": 500,
                        "placeholder": { "tag": "plain_text", "content": "驳回原因（可选，留空则用默认原因）" },
                        "default_value": ""
                      },
                      {
                        "tag": "button",
                        "name": "approve_button",
                        "form_action_type": "submit",
                        "type": "primary",
                        "text": { "tag": "plain_text", "content": "通过" },
                        "confirm": {
                          "title": { "tag": "plain_text", "content": "确认通过该报销单？" },
                          "text": { "tag": "plain_text", "content": "{reimbursementNo}，金额 {totalAmountText}" }
                        },
                        "behaviors": [
                          {
                            "type": "callback",
                            "value": {
                              "action": "approve",
                              "reimbursementId": "{reimbursementId}",
                              "submittedAt": "{submittedAtEpochSecond}",
                              "actionToken": "{approveActionToken}"
                            }
                          }
                        ]
                      },
                      {
                        "tag": "button",
                        "name": "reject_button",
                        "form_action_type": "submit",
                        "type": "danger",
                        "text": { "tag": "plain_text", "content": "驳回" },
                        "confirm": {
                          "title": { "tag": "plain_text", "content": "确认驳回该报销单？" },
                          "text": { "tag": "plain_text", "content": "驳回后申请人需修改并重新提交。" }
                        },
                        "behaviors": [
                          {
                            "type": "callback",
                            "value": {
                              "action": "reject",
                              "reimbursementId": "{reimbursementId}",
                              "submittedAt": "{submittedAtEpochSecond}",
                              "actionToken": "{rejectActionToken}"
                            }
                          }
                        ]
                      }
                    ]
                  },
                  {
                    "tag": "markdown",
                    "text_size": "notation",
                    "content": "仅绑定的审批人可操作，且不能审批本人提交的单据；动作将记入审计日志。"
                  }
                ]
              }
            }
            """;

    /**
     * Replaces the approval card in place once a decision is made. Must stay JSON 2.0: Feishu error
     * 200830 rejects updating a 2.0 card with 1.0 content. Carries no buttons, so it cannot be
     * clicked again.
     */
    private static final String APPROVAL_DECIDED_CARD = """
            {
              "schema": "2.0",
              "config": { "update_multi": true },
              "header": {
                "template": "{headerColor}",
                "title": { "tag": "plain_text", "content": "{decisionTitle}" },
                "subtitle": { "tag": "plain_text", "content": "{reimbursementNo}" }
              },
              "body": {
                "direction": "vertical",
                "elements": [
                  {
                    "tag": "markdown",
                    "content": "**申请人**：{applicant}\\n**项目**：{projectName}\\n**报销金额**：{totalAmountText}\\n**处理人**：{decidedBy}\\n**处理时间**：{decidedAt}\\n**{decisionDetailLabel}**：{decisionDetail}"
                  },
                  {
                    "tag": "markdown",
                    "text_size": "notation",
                    "content": "报销单 {reimbursementNo} 已处理完毕，按钮已失效。"
                  }
                ]
              }
            }
            """;

    private static final List<BuiltinTemplate> BUILTIN_TEMPLATES = List.of(
            new BuiltinTemplate(
                    "budget-warning",
                    "预算余额预警",
                    "预算管理",
                    "预算科目余额低于阈值时提醒负责人",
                    MESSAGE_TYPE_TEXT,
                    "【RCDIS 经费预警】项目：{projectName}，科目：{categoryName}，可用余额：{availableAmount} 元，请关注后续支出。"),
            new BuiltinTemplate(
                    "reimbursement-materials",
                    "报销材料缺失提醒",
                    "报销检查",
                    "报销单缺少附件、发票或说明材料时发送提醒",
                    MESSAGE_TYPE_TEXT,
                    "【RCDIS 报销提醒】报销单：{reimbursementNo} 缺少 {missingItems}，请补充后再提交。"),
            new BuiltinTemplate(
                    "approval-confirmation",
                    "审批确认通知",
                    "风险确认",
                    "高风险操作等待确认时推送到指定群聊",
                    MESSAGE_TYPE_TEXT,
                    "【RCDIS 审批确认】{operator} 发起 {operationName}，涉及金额 {amount} 元，请审批人及时确认。"),
            new BuiltinTemplate(
                    "workflow-status",
                    "Agent 流程状态",
                    "Agent 工作流",
                    "长任务启动、完成或失败时同步处理状态",
                    MESSAGE_TYPE_TEXT,
                    "【RCDIS Agent】任务 {workflowName} 当前状态：{status}。"),
            new BuiltinTemplate(
                    "funding-summary",
                    "经费周期摘要",
                    "定时汇总",
                    "按日或按周推送项目经费执行摘要",
                    MESSAGE_TYPE_TEXT,
                    "【RCDIS 经费摘要】{period} 共登记支出 {expenseCount} 笔，合计 {expenseAmount} 元。"),
            new BuiltinTemplate(
                    CODE_REIMBURSEMENT_SUBMITTED,
                    "报销单提交通知",
                    "报销审批",
                    "报销单提交成功后推送到审批群，等待审批处理",
                    MESSAGE_TYPE_INTERACTIVE,
                    SUBMITTED_CARD),
            new BuiltinTemplate(
                    CODE_REIMBURSEMENT_APPROVED,
                    "报销单通过通知",
                    "报销审批",
                    "报销单审批通过后告知申请人结果",
                    MESSAGE_TYPE_INTERACTIVE,
                    APPROVED_CARD),
            new BuiltinTemplate(
                    CODE_REIMBURSEMENT_REJECTED,
                    "报销单驳回通知",
                    "报销审批",
                    "报销单被驳回时告知申请人驳回原因",
                    MESSAGE_TYPE_INTERACTIVE,
                    REJECTED_CARD),
            new BuiltinTemplate(
                    CODE_REIMBURSEMENT_APPROVAL,
                    "报销审批交互卡片",
                    "报销审批",
                    "私聊推送给已绑定的审批人，卡片内含通过与驳回按钮（JSON 2.0）",
                    MESSAGE_TYPE_INTERACTIVE,
                    APPROVAL_CARD),
            new BuiltinTemplate(
                    CODE_REIMBURSEMENT_APPROVAL_DECIDED,
                    "报销审批已处理卡片",
                    "报销审批",
                    "审批完成后原地替换交互卡片，不再带按钮（JSON 2.0）",
                    MESSAGE_TYPE_INTERACTIVE,
                    APPROVAL_DECIDED_CARD)
    );

    private final NotificationTemplateMapper notificationTemplateMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        for (BuiltinTemplate template : BUILTIN_TEMPLATES) {
            validateInteractiveContent(template);
            seedTemplate(template);
        }
    }

    private void seedTemplate(BuiltinTemplate template) {
        Long existing = notificationTemplateMapper.selectCount(
                new LambdaQueryWrapper<NotificationTemplateEntity>()
                        .eq(NotificationTemplateEntity::getTemplateCode, template.code()));
        if (existing > 0) {
            return;
        }

        NotificationTemplateEntity entity = new NotificationTemplateEntity();
        entity.setTemplateCode(template.code());
        entity.setTemplateName(template.name());
        entity.setScene(template.scene());
        entity.setDescription(template.description());
        entity.setMessageType(template.messageType());
        entity.setContent(template.content().strip());
        entity.setBuiltin(Integer.valueOf(1));
        entity.setStatus("ACTIVE");
        entity.setVersion(Integer.valueOf(0));
        try {
            notificationTemplateMapper.insert(entity);
            log.atInfo()
                    .addKeyValue("templateCode", template.code())
                    .log("Builtin notification template seeded");
        } catch (DuplicateKeyException exception) {
            // Another instance seeded the same template concurrently; ignore.
            log.atInfo()
                    .addKeyValue("templateCode", template.code())
                    .log("Builtin notification template already present");
        }
    }

    private void validateInteractiveContent(BuiltinTemplate template) {
        if (!MESSAGE_TYPE_INTERACTIVE.equals(template.messageType())) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(template.content());
            if (!node.isObject()) {
                throw new IllegalStateException(
                        "Builtin card template must be a JSON object. templateCode=" + template.code());
            }
            validateCardSchema(node, template.code());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(
                    "Builtin card template is not valid JSON. templateCode=" + template.code(), exception);
        }
    }

    /**
     * Fails fast when a card declares schema 2.0 but still uses 1.0-only components.
     */
    private void validateCardSchema(JsonNode card, String templateCode) {
        if (!CARD_SCHEMA_V2.equals(card.path("schema").asText(null))) {
            return;
        }
        Set<String> offending = new LinkedHashSet<>();
        collectV1OnlyTags(card, offending);
        if (!offending.isEmpty()) {
            throw new IllegalStateException(
                    "Builtin card template declares schema 2.0 but uses card JSON 1.0 constructs that Feishu "
                            + "rejects with error 200861. templateCode=" + templateCode + ", tags=" + offending);
        }
    }

    private void collectV1OnlyTags(JsonNode node, Set<String> found) {
        if (node.isObject()) {
            JsonNode tag = node.get("tag");
            if (tag != null && tag.isTextual() && CARD_V1_ONLY_TAGS.contains(tag.asText())) {
                found.add(tag.asText());
            }
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                collectV1OnlyTags(node.get(fieldName), found);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectV1OnlyTags(item, found);
            }
        }
    }

    private record BuiltinTemplate(
            String code,
            String name,
            String scene,
            String description,
            String messageType,
            String content
    ) {
    }
}
