package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rcdis.agent.common.aop.AuditOperation;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.UserCreateRequest;
import com.rcdis.agent.dto.UserPasswordRequest;
import com.rcdis.agent.dto.UserRolesRequest;
import com.rcdis.agent.entity.SysRoleEntity;
import com.rcdis.agent.entity.SysUserEntity;
import com.rcdis.agent.entity.SysUserRoleEntity;
import com.rcdis.agent.mapper.SysRoleMapper;
import com.rcdis.agent.mapper.SysUserMapper;
import com.rcdis.agent.mapper.SysUserRoleMapper;
import com.rcdis.agent.service.UserService;
import com.rcdis.agent.vo.UserVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String DEFAULT_TENANT_ID = "default";
    private static final String TARGET_TYPE = "SYS_USER";
    private static final Integer FLAG_FALSE = Integer.valueOf(0);

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;

    // ---------- authentication lookups ----------

    @Override
    public Optional<SysUserEntity> findByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return Optional.empty();
        }
        SysUserEntity entity = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getUsername, username.trim())
                .eq(SysUserEntity::getDeleted, FLAG_FALSE)
                .orderByAsc(SysUserEntity::getId)
                .last("limit 1"));
        return Optional.ofNullable(entity);
    }

    @Override
    public List<String> findRoleCodes(Long userId) {
        if (userId == null) {
            return List.of();
        }
        // sys_user_role has no soft-delete column, so no deleted predicate is applied here.
        List<Long> roleIds = sysUserRoleMapper.selectList(new LambdaQueryWrapper<SysUserRoleEntity>()
                        .eq(SysUserRoleEntity::getUserId, userId))
                .stream()
                .map(SysUserRoleEntity::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return sysRoleMapper.selectList(new LambdaQueryWrapper<SysRoleEntity>()
                        .in(SysRoleEntity::getId, roleIds)
                        .eq(SysRoleEntity::getStatus, STATUS_ACTIVE)
                        .eq(SysRoleEntity::getDeleted, FLAG_FALSE))
                .stream()
                .map(SysRoleEntity::getCode)
                .toList();
    }

    @Override
    public boolean isPasswordValid(SysUserEntity user, String rawPassword) {
        if (user == null || !StringUtils.hasText(rawPassword) || !StringUtils.hasText(user.getPasswordHash())) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    @Override
    public void touchLastLogin(Long userId) {
        if (userId == null) {
            return;
        }
        // Only the login timestamp changes; a wrapper update avoids bumping the optimistic-lock version.
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, userId)
                .eq(SysUserEntity::getDeleted, FLAG_FALSE)
                .set(SysUserEntity::getLastLoginAt, OffsetDateTime.now()));
    }

    // ---------- administration ----------

    @Override
    public PageResponse<UserVO> pageUsers(long current, long size, String keyword) {
        validatePage(current, size);
        Page<SysUserEntity> page = new Page<>(current, size);
        LambdaQueryWrapper<SysUserEntity> wrapper = new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getDeleted, FLAG_FALSE);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(SysUserEntity::getUsername, kw)
                    .or().like(SysUserEntity::getDisplayName, kw));
        }
        wrapper.orderByAsc(SysUserEntity::getId);
        Page<SysUserEntity> entityPage = sysUserMapper.selectPage(page, wrapper);
        Map<Long, List<String>> rolesByUser = loadRoleCodesByUserIds(
                entityPage.getRecords().stream().map(SysUserEntity::getId).toList());
        Page<UserVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(user -> toVO(user, rolesByUser.getOrDefault(user.getId(), List.of())))
                .toList());
        return PageResponse.fromPage(voPage);
    }

    @Override
    public UserVO getUser(Long id) {
        SysUserEntity user = findEntity(id);
        return toVO(user, findRoleCodes(id));
    }

    @Override
    @Transactional
    @AuditOperation(action = "CREATE_USER", targetType = TARGET_TYPE)
    public UserVO createUser(UserCreateRequest request) {
        String username = request.username().trim();
        if (findByUsername(username).isPresent()) {
            throw new BusinessException(
                    "USER_USERNAME_EXISTS",
                    "用户名已存在: " + username,
                    HttpStatus.CONFLICT);
        }
        List<Long> roleIds = resolveRoleIds(request.roles());
        SysUserEntity entity = new SysUserEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity.setDisplayName(request.displayName().trim());
        entity.setTenantId(StringUtils.hasText(request.tenantId())
                ? request.tenantId().trim()
                : DEFAULT_TENANT_ID);
        entity.setStatus(STATUS_ACTIVE);
        entity.setVersion(FLAG_FALSE);
        sysUserMapper.insert(entity);
        insertUserRoles(entity.getId(), roleIds);
        log.atInfo()
                .addKeyValue("userId", entity.getId())
                .addKeyValue("username", username)
                .log("User created");
        return toVO(entity, findRoleCodes(entity.getId()));
    }

    @Override
    @Transactional
    @AuditOperation(action = "UPDATE_USER_PASSWORD", targetType = TARGET_TYPE)
    public void updatePassword(Long id, UserPasswordRequest request) {
        SysUserEntity user = findEntity(id);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        sysUserMapper.updateById(user);
        log.atInfo()
                .addKeyValue("userId", id)
                .log("User password reset");
    }

    @Override
    @Transactional
    @AuditOperation(action = "UPDATE_USER_STATUS", targetType = TARGET_TYPE)
    public UserVO toggleStatus(Long id) {
        SysUserEntity user = findEntity(id);
        String target = STATUS_ACTIVE.equals(user.getStatus()) ? STATUS_DISABLED : STATUS_ACTIVE;
        user.setStatus(target);
        sysUserMapper.updateById(user);
        log.atInfo()
                .addKeyValue("userId", id)
                .addKeyValue("status", target)
                .log("User status toggled");
        return toVO(user, findRoleCodes(id));
    }

    @Override
    @Transactional
    @AuditOperation(action = "ASSIGN_USER_ROLES", targetType = TARGET_TYPE)
    public UserVO assignRoles(Long id, UserRolesRequest request) {
        findEntity(id);
        List<Long> roleIds = resolveRoleIds(request.roles());
        // The join table is hard-deleted; replace the whole assignment set atomically.
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRoleEntity>()
                .eq(SysUserRoleEntity::getUserId, id));
        insertUserRoles(id, roleIds);
        log.atInfo()
                .addKeyValue("userId", id)
                .addKeyValue("roleCount", roleIds.size())
                .log("User roles assigned");
        return getUser(id);
    }

    // ---------- helpers ----------

    private SysUserEntity findEntity(Long id) {
        SysUserEntity entity = id == null ? null : sysUserMapper.selectOne(new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, id)
                .eq(SysUserEntity::getDeleted, FLAG_FALSE)
                .last("limit 1"));
        if (entity == null) {
            throw new BusinessException(
                    "USER_NOT_FOUND",
                    "用户不存在. id=" + id,
                    HttpStatus.NOT_FOUND);
        }
        return entity;
    }

    private List<Long> resolveRoleIds(List<String> roleCodes) {
        List<String> distinct = roleCodes == null ? List.of() : roleCodes.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            throw new BusinessException(
                    "USER_ROLES_REQUIRED",
                    "至少需要分配一个角色",
                    HttpStatus.BAD_REQUEST);
        }
        List<SysRoleEntity> roles = sysRoleMapper.selectList(new LambdaQueryWrapper<SysRoleEntity>()
                .in(SysRoleEntity::getCode, distinct)
                .eq(SysRoleEntity::getStatus, STATUS_ACTIVE)
                .eq(SysRoleEntity::getDeleted, FLAG_FALSE));
        if (roles.size() != distinct.size()) {
            throw new BusinessException(
                    "USER_ROLE_INVALID",
                    "包含无效或已停用的角色: " + distinct,
                    HttpStatus.BAD_REQUEST);
        }
        return roles.stream().map(SysRoleEntity::getId).toList();
    }

    private void insertUserRoles(Long userId, List<Long> roleIds) {
        for (Long roleId : roleIds) {
            SysUserRoleEntity link = new SysUserRoleEntity();
            link.setUserId(userId);
            link.setRoleId(roleId);
            sysUserRoleMapper.insert(link);
        }
    }

    private Map<Long, List<String>> loadRoleCodesByUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<SysUserRoleEntity> links = sysUserRoleMapper.selectList(new LambdaQueryWrapper<SysUserRoleEntity>()
                .in(SysUserRoleEntity::getUserId, userIds));
        if (links.isEmpty()) {
            return Map.of();
        }
        List<Long> roleIds = links.stream().map(SysUserRoleEntity::getRoleId).distinct().toList();
        Map<Long, String> codeByRoleId = sysRoleMapper.selectList(new LambdaQueryWrapper<SysRoleEntity>()
                        .in(SysRoleEntity::getId, roleIds)
                        .eq(SysRoleEntity::getDeleted, FLAG_FALSE))
                .stream()
                .collect(Collectors.toMap(SysRoleEntity::getId, SysRoleEntity::getCode, (first, second) -> first));
        Map<Long, List<String>> result = new HashMap<>();
        for (SysUserRoleEntity link : links) {
            String code = codeByRoleId.get(link.getRoleId());
            if (code != null) {
                result.computeIfAbsent(link.getUserId(), key -> new ArrayList<>()).add(code);
            }
        }
        return result;
    }

    private UserVO toVO(SysUserEntity user, List<String> roles) {
        return new UserVO(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getTenantId(),
                user.getStatus(),
                roles,
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getVersion());
    }

    private void validatePage(long current, long size) {
        if (current < 1) {
            throw new BusinessException("PAGE_CURRENT_INVALID", "Page current must be greater than 0");
        }
        if (size < 1 || size > 500) {
            throw new BusinessException("PAGE_SIZE_INVALID", "Page size must be between 1 and 500");
        }
    }
}
