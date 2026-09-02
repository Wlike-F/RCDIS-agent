package com.rcdis.agent.service;

import java.util.List;

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

    private static final String MESSAGE_TYPE_TEXT = "text";
    private static final String MESSAGE_TYPE_INTERACTIVE = "interactive";

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
                    REJECTED_CARD)
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
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(
                    "Builtin card template is not valid JSON. templateCode=" + template.code(), exception);
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
