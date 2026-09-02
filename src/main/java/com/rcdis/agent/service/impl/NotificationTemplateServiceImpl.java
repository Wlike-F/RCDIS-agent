package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.aop.AuditOperation;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.dto.NotificationTemplateCreateRequest;
import com.rcdis.agent.dto.NotificationTemplateDeleteRequest;
import com.rcdis.agent.dto.NotificationTemplateUpdateRequest;
import com.rcdis.agent.entity.NotificationTemplateEntity;
import com.rcdis.agent.mapper.NotificationTemplateMapper;
import com.rcdis.agent.service.NotificationTemplateService;
import com.rcdis.agent.vo.FeishuNotificationTemplateVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationTemplateServiceImpl implements NotificationTemplateService {

    private static final String MESSAGE_TYPE_INTERACTIVE = "interactive";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    private final NotificationTemplateMapper notificationTemplateMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<FeishuNotificationTemplateVO> listTemplates() {
        return notificationTemplateMapper.selectList(new LambdaQueryWrapper<NotificationTemplateEntity>()
                        .orderByDesc(NotificationTemplateEntity::getBuiltin)
                        .orderByAsc(NotificationTemplateEntity::getId))
                .stream()
                .map(FeishuNotificationTemplateVO::fromEntity)
                .toList();
    }

    @Override
    public FeishuNotificationTemplateVO getTemplate(Long id) {
        return FeishuNotificationTemplateVO.fromEntity(findTemplateEntity(id));
    }

    @Override
    @Transactional
    @AuditOperation(action = "CREATE_NOTIFICATION_TEMPLATE", targetType = "NOTIFICATION_TEMPLATE")
    public FeishuNotificationTemplateVO createTemplate(NotificationTemplateCreateRequest request) {
        String templateCode = request.templateCode().trim();
        ensureTemplateCodeIsAvailable(templateCode);
        validateContent(request.messageType(), request.content());

        NotificationTemplateEntity entity = new NotificationTemplateEntity();
        entity.setTemplateCode(templateCode);
        entity.setTemplateName(request.templateName().trim());
        entity.setScene(normalizeOptionalText(request.scene()));
        entity.setDescription(normalizeOptionalText(request.description()));
        entity.setMessageType(request.messageType());
        entity.setContent(request.content());
        entity.setBuiltin(Integer.valueOf(0));
        entity.setStatus(StringUtils.hasText(request.status()) ? request.status() : STATUS_ACTIVE);
        entity.setVersion(Integer.valueOf(0));
        notificationTemplateMapper.insert(entity);

        log.atInfo()
                .addKeyValue("templateId", entity.getId())
                .addKeyValue("templateCode", templateCode)
                .log("Notification template created");
        return FeishuNotificationTemplateVO.fromEntity(entity);
    }

    @Override
    @Transactional
    @AuditOperation(action = "UPDATE_NOTIFICATION_TEMPLATE", targetType = "NOTIFICATION_TEMPLATE")
    public FeishuNotificationTemplateVO updateTemplate(Long id, NotificationTemplateUpdateRequest request) {
        NotificationTemplateEntity existing = findTemplateEntity(id);
        validateContent(request.messageType(), request.content());

        NotificationTemplateEntity updateEntity = new NotificationTemplateEntity();
        updateEntity.setId(id);
        updateEntity.setTemplateName(request.templateName().trim());
        updateEntity.setScene(normalizeOptionalText(request.scene()));
        updateEntity.setDescription(normalizeOptionalText(request.description()));
        updateEntity.setMessageType(request.messageType());
        updateEntity.setContent(request.content());
        updateEntity.setStatus(StringUtils.hasText(request.status()) ? request.status() : existing.getStatus());
        updateEntity.setVersion(request.version());

        int updated = notificationTemplateMapper.updateById(updateEntity);
        if (updated != 1) {
            throw new BusinessException(
                    "NOTIFICATION_TEMPLATE_VERSION_CONFLICT",
                    "Notification template was changed by another request. templateId=" + id,
                    HttpStatus.CONFLICT);
        }

        log.atInfo()
                .addKeyValue("templateId", id)
                .addKeyValue("templateCode", existing.getTemplateCode())
                .log("Notification template updated");
        return FeishuNotificationTemplateVO.fromEntity(notificationTemplateMapper.selectById(id));
    }

    @Override
    @Transactional
    @AuditOperation(action = "UPDATE_NOTIFICATION_TEMPLATE_STATUS", targetType = "NOTIFICATION_TEMPLATE")
    public FeishuNotificationTemplateVO toggleTemplateStatus(Long id) {
        NotificationTemplateEntity existing = findTemplateEntity(id);
        String targetStatus = STATUS_ACTIVE.equals(existing.getStatus()) ? STATUS_DISABLED : STATUS_ACTIVE;

        NotificationTemplateEntity updateEntity = new NotificationTemplateEntity();
        updateEntity.setId(id);
        updateEntity.setStatus(targetStatus);
        updateEntity.setVersion(existing.getVersion());

        int updated = notificationTemplateMapper.updateById(updateEntity);
        if (updated != 1) {
            throw new BusinessException(
                    "NOTIFICATION_TEMPLATE_VERSION_CONFLICT",
                    "Notification template was changed by another request. templateId=" + id,
                    HttpStatus.CONFLICT);
        }

        log.atInfo()
                .addKeyValue("templateId", id)
                .addKeyValue("templateCode", existing.getTemplateCode())
                .addKeyValue("status", targetStatus)
                .log("Notification template status toggled");
        return FeishuNotificationTemplateVO.fromEntity(notificationTemplateMapper.selectById(id));
    }

    @Override
    @Transactional
    @AuditOperation(action = "DELETE_NOTIFICATION_TEMPLATE", targetType = "NOTIFICATION_TEMPLATE")
    public void deleteTemplate(Long id, NotificationTemplateDeleteRequest request) {
        NotificationTemplateEntity existing = findTemplateEntity(id);
        if (Integer.valueOf(1).equals(existing.getBuiltin())) {
            throw new BusinessException(
                    "NOTIFICATION_TEMPLATE_BUILTIN_PROTECTED",
                    "内置模板不可删除，可将其停用。templateId=" + id
                            + ", templateCode=" + existing.getTemplateCode());
        }

        OffsetDateTime now = OffsetDateTime.now();
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        int updated = notificationTemplateMapper.update(null, new LambdaUpdateWrapper<NotificationTemplateEntity>()
                .eq(NotificationTemplateEntity::getId, id)
                .eq(NotificationTemplateEntity::getDeleted, Integer.valueOf(0))
                .set(NotificationTemplateEntity::getDeleted, Integer.valueOf(1))
                .set(NotificationTemplateEntity::getDeletedAt, now)
                .set(NotificationTemplateEntity::getDeletedBy, currentUserId)
                .set(NotificationTemplateEntity::getDeleteReason, request.reason().trim())
                .set(NotificationTemplateEntity::getUpdatedAt, now)
                .set(NotificationTemplateEntity::getUpdatedBy, currentUserId));
        if (updated != 1) {
            throw new BusinessException(
                    "NOTIFICATION_TEMPLATE_DELETE_CONFLICT",
                    "Notification template was changed by another request. templateId=" + id,
                    HttpStatus.CONFLICT);
        }

        log.atInfo()
                .addKeyValue("templateId", id)
                .addKeyValue("templateCode", existing.getTemplateCode())
                .log("Notification template deleted");
    }

    // ---------- helpers ----------

    private NotificationTemplateEntity findTemplateEntity(Long id) {
        NotificationTemplateEntity entity = notificationTemplateMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(
                    "NOTIFICATION_TEMPLATE_NOT_FOUND",
                    "Notification template was not found. templateId=" + id,
                    HttpStatus.NOT_FOUND);
        }
        return entity;
    }

    private void ensureTemplateCodeIsAvailable(String templateCode) {
        Long count = notificationTemplateMapper.selectCount(new LambdaQueryWrapper<NotificationTemplateEntity>()
                .eq(NotificationTemplateEntity::getTemplateCode, templateCode));
        if (count > 0) {
            throw new BusinessException(
                    "NOTIFICATION_TEMPLATE_CODE_EXISTS",
                    "Notification template code already exists. templateCode=" + templateCode,
                    HttpStatus.CONFLICT);
        }
    }

    private void validateContent(String messageType, String content) {
        if (!MESSAGE_TYPE_INTERACTIVE.equals(messageType)) {
            return;
        }
        try {
            if (!objectMapper.readTree(content).isObject()) {
                throw new BusinessException(
                        "NOTIFICATION_TEMPLATE_CONTENT_INVALID",
                        "卡片模板内容必须是 JSON 对象");
            }
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "NOTIFICATION_TEMPLATE_CONTENT_INVALID",
                    "卡片模板内容不是合法 JSON：" + exception.getOriginalMessage());
        }
    }

    private String normalizeOptionalText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
