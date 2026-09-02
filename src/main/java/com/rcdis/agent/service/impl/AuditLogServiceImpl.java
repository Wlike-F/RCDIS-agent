package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.entity.AuditLogEntity;
import com.rcdis.agent.mapper.AuditLogMapper;
import com.rcdis.agent.service.AuditLogService;
import com.rcdis.agent.to.AuditLogEntryTO;
import com.rcdis.agent.vo.AuditLogVO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditLogEntryTO entry) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setActor(entry.actor());
        entity.setTenantId(entry.tenantId());
        entity.setAction(entry.action());
        entity.setTargetType(entry.targetType());
        entity.setTargetId(entry.targetId());
        entity.setBeforeSnapshot(writeJson(entry.beforeSnapshot()));
        entity.setAfterSnapshot(writeJson(entry.afterSnapshot()));
        entity.setReason(entry.reason());
        entity.setSource(entry.source());
        entity.setConversationId(entry.conversationId());
        entity.setCreatedAt(OffsetDateTime.now());
        auditLogMapper.insert(entity);
    }

    @Override
    public PageResponse<AuditLogVO> list(long current, long size) {
        validatePage(current, size);
        Page<AuditLogEntity> page = new Page<>(current, size);
        LambdaQueryWrapper<AuditLogEntity> wrapper = new LambdaQueryWrapper<AuditLogEntity>()
                .orderByDesc(AuditLogEntity::getCreatedAt)
                .orderByDesc(AuditLogEntity::getId);
        Page<AuditLogEntity> entityPage = auditLogMapper.selectPage(page, wrapper);
        Page<AuditLogVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(AuditLogVO::fromEntity)
                .toList());
        return PageResponse.fromPage(voPage);
    }

    private void validatePage(long current, long size) {
        if (current < 1) {
            throw new BusinessException("PAGE_CURRENT_INVALID", "Page current must be greater than 0");
        }
        if (size < 1 || size > 500) {
            throw new BusinessException("PAGE_SIZE_INVALID", "Page size must be between 1 and 500");
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "AUDIT_JSON_SERIALIZE_FAILED",
                    "Failed to serialize audit snapshot",
                    exception);
        }
    }
}
