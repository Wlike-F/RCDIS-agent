package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rcdis.agent.common.aop.AuditOperation;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.FeishuProperties;
import com.rcdis.agent.dto.FeishuApproverBindRequest;
import com.rcdis.agent.dto.FeishuApproverDeleteRequest;
import com.rcdis.agent.entity.FeishuApproverEntity;
import com.rcdis.agent.infrastructure.feishu.FeishuChatMemberClient;
import com.rcdis.agent.mapper.FeishuApproverMapper;
import com.rcdis.agent.service.FeishuApproverService;
import com.rcdis.agent.to.FeishuApproverTO;
import com.rcdis.agent.to.FeishuChatMemberTO;
import com.rcdis.agent.vo.FeishuApproverVO;
import com.rcdis.agent.vo.FeishuChatMemberVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuApproverServiceImpl implements FeishuApproverService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String ROLE_APPROVER = "APPROVER";
    private static final String DEFAULT_TENANT_ID = "default";
    private static final String CHAT_ID_TYPE = "chat_id";
    private static final String TARGET_TYPE = "FEISHU_APPROVER";
    private static final Integer FLAG_TRUE = Integer.valueOf(1);
    private static final Integer FLAG_FALSE = Integer.valueOf(0);

    private final FeishuApproverMapper feishuApproverMapper;
    private final FeishuProperties feishuProperties;
    /** Only present when the app bot integration is enabled, so it is injected lazily. */
    private final ObjectProvider<FeishuChatMemberClient> chatMemberClientProvider;

    @Override
    public List<FeishuApproverVO> listApprovers() {
        return feishuApproverMapper.selectList(new LambdaQueryWrapper<FeishuApproverEntity>()
                        .orderByAsc(FeishuApproverEntity::getStatus)
                        .orderByAsc(FeishuApproverEntity::getId))
                .stream()
                .map(FeishuApproverVO::fromEntity)
                .toList();
    }

    @Override
    public List<FeishuChatMemberVO> listChatMembers() {
        FeishuChatMemberClient client = chatMemberClientProvider.getIfAvailable();
        if (client == null) {
            throw new BusinessException(
                    "FEISHU_APP_MODE_REQUIRED",
                    "拉取群成员需要启用飞书自建应用：rcdis.feishu.enabled=true 且 client-type=app。");
        }
        List<FeishuChatMemberTO> members = client.listMembers(resolveApprovalChatId());
        Map<String, FeishuApproverEntity> boundByOpenId = feishuApproverMapper.selectList(
                        new LambdaQueryWrapper<FeishuApproverEntity>())
                .stream()
                .collect(Collectors.toMap(
                        FeishuApproverEntity::getOpenId,
                        Function.identity(),
                        (first, second) -> first));
        return members.stream()
                .map(member -> {
                    FeishuApproverEntity bound = boundByOpenId.get(member.openId());
                    return new FeishuChatMemberVO(
                            member.openId(),
                            member.name(),
                            bound != null,
                            bound == null ? null : bound.getId(),
                            bound == null ? null : bound.getRole());
                })
                .toList();
    }

    @Override
    @Transactional
    @AuditOperation(action = "BIND_FEISHU_APPROVER", targetType = TARGET_TYPE)
    public FeishuApproverVO bindApprover(FeishuApproverBindRequest request) {
        String openId = request.openId().trim();
        ensureOpenIdIsAvailable(openId);

        CurrentUserTO currentUser = CurrentUserContextHolder.currentOrAnonymous();
        FeishuApproverEntity entity = new FeishuApproverEntity();
        entity.setOpenId(openId);
        entity.setUserId(request.userId().trim());
        entity.setUserName(request.userName().trim());
        entity.setTenantId(StringUtils.hasText(request.tenantId())
                ? request.tenantId().trim()
                : resolveTenantId(currentUser));
        entity.setRole(StringUtils.hasText(request.role()) ? request.role() : ROLE_APPROVER);
        entity.setRemark(StringUtils.hasText(request.remark()) ? request.remark().trim() : null);
        entity.setStatus(STATUS_ACTIVE);
        entity.setVersion(FLAG_FALSE);
        feishuApproverMapper.insert(entity);

        log.atInfo()
                .addKeyValue("approverId", entity.getId())
                .addKeyValue("userId", entity.getUserId())
                .addKeyValue("role", entity.getRole())
                .log("Feishu approver bound");
        return FeishuApproverVO.fromEntity(entity);
    }

    @Override
    @Transactional
    @AuditOperation(action = "UPDATE_FEISHU_APPROVER_STATUS", targetType = TARGET_TYPE)
    public FeishuApproverVO toggleApproverStatus(Long id) {
        FeishuApproverEntity existing = findEntity(id);
        String targetStatus = STATUS_ACTIVE.equals(existing.getStatus()) ? STATUS_DISABLED : STATUS_ACTIVE;
        OffsetDateTime now = OffsetDateTime.now();
        requireSingleRow(feishuApproverMapper.update(null, new LambdaUpdateWrapper<FeishuApproverEntity>()
                .eq(FeishuApproverEntity::getId, id)
                .eq(FeishuApproverEntity::getDeleted, FLAG_FALSE)
                .eq(FeishuApproverEntity::getVersion, existing.getVersion())
                .set(FeishuApproverEntity::getStatus, targetStatus)
                .set(FeishuApproverEntity::getVersion, existing.getVersion() + 1)
                .set(FeishuApproverEntity::getUpdatedAt, now)
                .set(FeishuApproverEntity::getUpdatedBy, CurrentUserContextHolder.currentOrAnonymous().userId())), id);

        log.atInfo()
                .addKeyValue("approverId", id)
                .addKeyValue("status", targetStatus)
                .log("Feishu approver status toggled");
        return FeishuApproverVO.fromEntity(feishuApproverMapper.selectById(id));
    }

    @Override
    @Transactional
    @AuditOperation(action = "UNBIND_FEISHU_APPROVER", targetType = TARGET_TYPE)
    public void unbindApprover(Long id, FeishuApproverDeleteRequest request) {
        findEntity(id);
        OffsetDateTime now = OffsetDateTime.now();
        CurrentUserTO currentUser = CurrentUserContextHolder.currentOrAnonymous();
        requireSingleRow(feishuApproverMapper.update(null, new LambdaUpdateWrapper<FeishuApproverEntity>()
                .eq(FeishuApproverEntity::getId, id)
                .eq(FeishuApproverEntity::getDeleted, FLAG_FALSE)
                .eq(FeishuApproverEntity::getVersion, request.version())
                .set(FeishuApproverEntity::getDeleted, FLAG_TRUE)
                .set(FeishuApproverEntity::getDeletedAt, now)
                .set(FeishuApproverEntity::getDeletedBy, currentUser.userId())
                .set(FeishuApproverEntity::getDeleteReason, request.reason().trim())
                .set(FeishuApproverEntity::getUpdatedAt, now)
                .set(FeishuApproverEntity::getUpdatedBy, currentUser.userId())), id);

        log.atInfo()
                .addKeyValue("approverId", id)
                .log("Feishu approver unbound");
    }

    @Override
    public Optional<FeishuApproverTO> findActiveByOpenId(String openId) {
        if (!StringUtils.hasText(openId)) {
            return Optional.empty();
        }
        FeishuApproverEntity entity = feishuApproverMapper.selectOne(new LambdaQueryWrapper<FeishuApproverEntity>()
                .eq(FeishuApproverEntity::getOpenId, openId.trim())
                .eq(FeishuApproverEntity::getStatus, STATUS_ACTIVE)
                .eq(FeishuApproverEntity::getDeleted, FLAG_FALSE)
                .orderByAsc(FeishuApproverEntity::getId)
                .last("limit 1"));
        return Optional.ofNullable(entity).map(FeishuApproverServiceImpl::toTO);
    }

    @Override
    public List<FeishuApproverTO> listActiveApprovers() {
        return feishuApproverMapper.selectList(new LambdaQueryWrapper<FeishuApproverEntity>()
                        .eq(FeishuApproverEntity::getStatus, STATUS_ACTIVE)
                        .orderByAsc(FeishuApproverEntity::getId))
                .stream()
                .map(FeishuApproverServiceImpl::toTO)
                .toList();
    }

    // ---------- helpers ----------

    private static FeishuApproverTO toTO(FeishuApproverEntity entity) {
        return new FeishuApproverTO(
                entity.getOpenId(),
                entity.getUserId(),
                entity.getUserName(),
                entity.getTenantId(),
                entity.getRole());
    }

    private FeishuApproverEntity findEntity(Long id) {
        FeishuApproverEntity entity = id == null ? null : feishuApproverMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(
                    "FEISHU_APPROVER_NOT_FOUND",
                    "Approver binding was not found. id=" + id,
                    HttpStatus.NOT_FOUND);
        }
        return entity;
    }

    private void ensureOpenIdIsAvailable(String openId) {
        Long count = feishuApproverMapper.selectCount(new LambdaQueryWrapper<FeishuApproverEntity>()
                .eq(FeishuApproverEntity::getOpenId, openId)
                .eq(FeishuApproverEntity::getDeleted, FLAG_FALSE));
        if (count > 0) {
            throw new BusinessException(
                    "FEISHU_APPROVER_ALREADY_BOUND",
                    "该飞书用户已绑定为审批人，请先解绑或停用原绑定。",
                    HttpStatus.CONFLICT);
        }
    }

    private String resolveApprovalChatId() {
        String receiveIdType = feishuProperties.getDefaultReceiveIdType();
        if (!StringUtils.hasText(receiveIdType) || !CHAT_ID_TYPE.equalsIgnoreCase(receiveIdType.trim())) {
            throw new BusinessException(
                    "FEISHU_APPROVAL_CHAT_NOT_CONFIGURED",
                    "拉取群成员要求 rcdis.feishu.default-receive-id-type=chat_id，当前为 " + receiveIdType);
        }
        if (!StringUtils.hasText(feishuProperties.getDefaultReceiveId())) {
            throw new BusinessException(
                    "FEISHU_APPROVAL_CHAT_NOT_CONFIGURED",
                    "请先配置 rcdis.feishu.default-receive-id 为审批群的 oc_ 标识。");
        }
        return feishuProperties.getDefaultReceiveId().trim();
    }

    private String resolveTenantId(CurrentUserTO currentUser) {
        return StringUtils.hasText(currentUser.tenantId()) ? currentUser.tenantId() : DEFAULT_TENANT_ID;
    }

    private void requireSingleRow(int updated, Long id) {
        if (updated != 1) {
            throw new BusinessException(
                    "FEISHU_APPROVER_VERSION_CONFLICT",
                    "审批人绑定已被其他请求修改，请刷新后重试。id=" + id,
                    HttpStatus.CONFLICT);
        }
    }
}
