package com.rcdis.agent.service.impl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.entity.AppNotificationEntity;
import com.rcdis.agent.entity.ReimbursementOrderEntity;
import com.rcdis.agent.entity.SysRoleEntity;
import com.rcdis.agent.entity.SysUserEntity;
import com.rcdis.agent.entity.SysUserRoleEntity;
import com.rcdis.agent.mapper.AppNotificationMapper;
import com.rcdis.agent.mapper.ReimbursementOrderMapper;
import com.rcdis.agent.mapper.SysRoleMapper;
import com.rcdis.agent.mapper.SysUserMapper;
import com.rcdis.agent.mapper.SysUserRoleMapper;
import com.rcdis.agent.service.InAppNotificationService;
import com.rcdis.agent.service.NotificationStreamRegistry;
import com.rcdis.agent.vo.AppNotificationVO;
import com.rcdis.agent.vo.ReimbursementDetailVO;
import com.rcdis.agent.vo.ReimbursementVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class InAppNotificationServiceImpl implements InAppNotificationService {

    /** Same role pair that guards {@code POST /api/reimbursements/{id}/approve|reject}. */
    private static final List<String> APPROVAL_ROLE_CODES = List.of("APPROVER", "ADMIN");
    private static final String USER_STATUS_ACTIVE = "ACTIVE";

    private final AppNotificationMapper appNotificationMapper;
    private final ReimbursementOrderMapper reimbursementOrderMapper;
    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final NotificationStreamRegistry notificationStreamRegistry;

    @Override
    public void notifySubmitted(ReimbursementDetailVO detail) {
        ReimbursementVO order = detail.order();
        // The receipt goes to the applicant: the acting user (equals the applicant in every entry
        // point) plus the resolved applicant names as a fallback.
        Set<String> recipients = new LinkedHashSet<>();
        String actor = CurrentUserContextHolder.currentOrAnonymous().userId();
        if (StringUtils.hasText(actor)) {
            recipients.add(actor);
        }
        Set<String> applicantUsernames = resolveApplicantUsernames(order.id());
        recipients.addAll(applicantUsernames);
        // Approval todos go to the holders of the approval roles, excluding the applicant to avoid
        // self-noise.
        listApproverUsernames().stream()
                .filter(approverId -> !applicantUsernames.contains(approverId))
                .forEach(recipients::add);
        publish(AppNotificationEntity.TYPE_SUBMITTED, order, recipients,
                "报销单 " + order.reimbursementNo() + " 已提交审批",
                "等待审批，项目 " + projectName(order) + "，金额 " + amount(order));
    }

    @Override
    public void notifyApproved(ReimbursementDetailVO detail) {
        ReimbursementVO order = detail.order();
        publish(AppNotificationEntity.TYPE_APPROVED, order, resolveApplicantUsernames(order.id()),
                "报销单 " + order.reimbursementNo() + " 审批通过",
                "项目 " + projectName(order) + "，金额 " + amount(order) + " 已入账");
    }

    @Override
    public void notifyRejected(ReimbursementDetailVO detail) {
        ReimbursementVO order = detail.order();
        String reason = order.rejectReason() == null ? "" : order.rejectReason();
        publish(AppNotificationEntity.TYPE_REJECTED, order, resolveApplicantUsernames(order.id()),
                "报销单 " + order.reimbursementNo() + " 审批驳回",
                "项目 " + projectName(order) + "，金额 " + amount(order) + "，原因：" + reason);
    }

    @Override
    public void notifyVoided(ReimbursementDetailVO detail) {
        ReimbursementVO order = detail.order();
        publish(AppNotificationEntity.TYPE_VOIDED, order, resolveApplicantUsernames(order.id()),
                "报销单 " + order.reimbursementNo() + " 已作废",
                "项目 " + projectName(order) + "，金额 " + amount(order) + "，预算占用已释放");
    }

    @Override
    public PageResponse<AppNotificationVO> pageMyNotifications(long current, long size) {
        String recipient = CurrentUserContextHolder.currentOrAnonymous().userId();
        Page<AppNotificationEntity> page = appNotificationMapper.selectPage(
                new Page<>(current, size),
                new LambdaQueryWrapper<AppNotificationEntity>()
                        .eq(AppNotificationEntity::getRecipient, recipient)
                        .orderByDesc(AppNotificationEntity::getId));
        return PageResponse.fromPage(page.convert(AppNotificationVO::fromEntity));
    }

    @Override
    public long countUnread() {
        String recipient = CurrentUserContextHolder.currentOrAnonymous().userId();
        return appNotificationMapper.selectCount(
                new LambdaQueryWrapper<AppNotificationEntity>()
                        .eq(AppNotificationEntity::getRecipient, recipient)
                        .eq(AppNotificationEntity::getIsRead, Boolean.FALSE));
    }

    @Override
    public void markRead(Long id) {
        AppNotificationEntity notification = findOwnedNotification(id);
        if (Boolean.TRUE.equals(notification.getIsRead())) {
            return;
        }
        appNotificationMapper.update(null, new LambdaUpdateWrapper<AppNotificationEntity>()
                .eq(AppNotificationEntity::getId, id)
                .set(AppNotificationEntity::getIsRead, Boolean.TRUE));
    }

    @Override
    public void markAllRead() {
        String recipient = CurrentUserContextHolder.currentOrAnonymous().userId();
        appNotificationMapper.update(null, new LambdaUpdateWrapper<AppNotificationEntity>()
                .eq(AppNotificationEntity::getRecipient, recipient)
                .eq(AppNotificationEntity::getIsRead, Boolean.FALSE)
                .set(AppNotificationEntity::getIsRead, Boolean.TRUE));
    }

    private AppNotificationEntity findOwnedNotification(Long id) {
        String recipient = CurrentUserContextHolder.currentOrAnonymous().userId();
        AppNotificationEntity notification = appNotificationMapper.selectOne(
                new LambdaQueryWrapper<AppNotificationEntity>()
                        .eq(AppNotificationEntity::getId, id)
                        .eq(AppNotificationEntity::getRecipient, recipient));
        if (notification == null) {
            throw new BusinessException(
                    "APP_NOTIFICATION_NOT_FOUND",
                    "通知不存在。notificationId=" + id,
                    HttpStatus.NOT_FOUND);
        }
        return notification;
    }

    private void publish(String type, ReimbursementVO order, Set<String> recipients, String title, String content) {
        if (recipients.isEmpty()) {
            log.atInfo()
                    .addKeyValue("reimbursementId", order.id())
                    .addKeyValue("type", type)
                    .log("No in-app notification recipient resolved; skipped");
            return;
        }
        for (String recipient : recipients) {
            AppNotificationEntity entity = new AppNotificationEntity();
            entity.setRecipient(recipient);
            entity.setType(type);
            entity.setTitle(truncate(title));
            entity.setContent(truncate(content));
            entity.setBizType(AppNotificationEntity.BIZ_TYPE_REIMBURSEMENT);
            entity.setBizId(order.id());
            entity.setIsRead(Boolean.FALSE);
            appNotificationMapper.insert(entity);
            // Row committed first, then an accelerated push to whoever is online; the registry
            // swallows offline/failed connections, so this never breaks the caller.
            notificationStreamRegistry.push(
                    recipient, "notification", AppNotificationVO.fromEntity(entity));
        }
        log.atInfo()
                .addKeyValue("reimbursementId", order.id())
                .addKeyValue("type", type)
                .addKeyValue("recipients", recipients)
                .log("In-app notifications published");
    }

    /**
     * Resolves the applicant free-text to login names. The order applicant may hold either the
     * username or the display name, so both columns are matched; the row creator is the fallback.
     */
    private Set<String> resolveApplicantUsernames(Long orderId) {
        Set<String> usernames = new LinkedHashSet<>();
        ReimbursementOrderEntity order = reimbursementOrderMapper.selectById(orderId);
        if (order == null) {
            return usernames;
        }
        String applicant = order.getApplicant();
        if (StringUtils.hasText(applicant)) {
            List<SysUserEntity> matched = sysUserMapper.selectList(
                    new LambdaQueryWrapper<SysUserEntity>()
                            .and(wrapper -> wrapper
                                    .eq(SysUserEntity::getUsername, applicant)
                                    .or()
                                    .eq(SysUserEntity::getDisplayName, applicant))
                            .last("LIMIT 5"));
            matched.forEach(user -> usernames.add(user.getUsername()));
        }
        if (usernames.isEmpty() && StringUtils.hasText(order.getCreatedBy())) {
            usernames.add(order.getCreatedBy());
        }
        return usernames;
    }

    /**
     * Login names of the active accounts allowed to act on an approval. This mirrors the URL-layer
     * authorization of the approve/reject endpoints (ADMIN or APPROVER role); the Feishu approver
     * binding table is deliberately not consulted, because its {@code user_id} holds Feishu identities
     * that may not exist as in-app accounts and it only authorizes card callbacks.
     */
    private Set<String> listApproverUsernames() {
        Set<String> usernames = new LinkedHashSet<>();
        List<Long> roleIds = sysRoleMapper.selectList(new LambdaQueryWrapper<SysRoleEntity>()
                        .in(SysRoleEntity::getCode, APPROVAL_ROLE_CODES))
                .stream()
                .map(SysRoleEntity::getId)
                .toList();
        if (roleIds.isEmpty()) {
            return usernames;
        }
        List<Long> userIds = sysUserRoleMapper.selectList(new LambdaQueryWrapper<SysUserRoleEntity>()
                        .in(SysUserRoleEntity::getRoleId, roleIds))
                .stream()
                .map(SysUserRoleEntity::getUserId)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return usernames;
        }
        sysUserMapper.selectList(new LambdaQueryWrapper<SysUserEntity>()
                        .in(SysUserEntity::getId, userIds)
                        .eq(SysUserEntity::getStatus, USER_STATUS_ACTIVE))
                .forEach(user -> usernames.add(user.getUsername()));
        return usernames;
    }

    private static String projectName(ReimbursementVO order) {
        return StringUtils.hasText(order.projectName()) ? order.projectName() : "--";
    }

    private static String amount(ReimbursementVO order) {
        return order.totalAmount() == null ? "--" : order.totalAmount().toPlainString();
    }

    private static String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
