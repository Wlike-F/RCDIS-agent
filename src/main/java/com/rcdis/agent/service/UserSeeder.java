package com.rcdis.agent.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rcdis.agent.config.SecurityProperties;
import com.rcdis.agent.entity.SysRoleEntity;
import com.rcdis.agent.entity.SysUserEntity;
import com.rcdis.agent.entity.SysUserRoleEntity;
import com.rcdis.agent.mapper.SysRoleMapper;
import com.rcdis.agent.mapper.SysUserMapper;
import com.rcdis.agent.mapper.SysUserRoleMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds the three built-in roles and any accounts declared under {@code rcdis.security.seed-users}.
 *
 * <p>Runs on startup and only inserts what is missing, so roles and accounts created or edited
 * through {@code /api/users} always win. Passwords are hashed with BCrypt before storage; a seed
 * password is never applied to an account that already exists.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSeeder implements ApplicationRunner {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String DEFAULT_TENANT_ID = "default";
    private static final Integer FLAG_FALSE = Integer.valueOf(0);

    /** Built-in roles in descending privilege order. {@code code} is stable and matched by hasRole(...). */
    private static final List<BuiltinRole> BUILTIN_ROLES = List.of(
            new BuiltinRole("ADMIN", "系统管理员",
                    "拥有全部权限，含用户管理、模型供应商、飞书配置、项目与预算维护"),
            new BuiltinRole("APPROVER", "带审批的科研人员",
                    "拥有科研人员的全部权限，外加报销审批/驳回与审计日志查看"),
            new BuiltinRole("RESEARCHER", "科研人员",
                    "日常业务操作（支出、报销、凭证、对话）与全量只读"));

    private final SecurityProperties securityProperties;
    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        seedRoles();
        seedUsers();
    }

    private void seedRoles() {
        for (BuiltinRole role : BUILTIN_ROLES) {
            if (findRoleByCode(role.code()) != null) {
                continue;
            }
            SysRoleEntity entity = new SysRoleEntity();
            entity.setCode(role.code());
            entity.setName(role.name());
            entity.setDescription(role.description());
            entity.setStatus(STATUS_ACTIVE);
            entity.setVersion(FLAG_FALSE);
            try {
                sysRoleMapper.insert(entity);
                log.atInfo().addKeyValue("roleCode", role.code()).log("Built-in role seeded");
            } catch (DuplicateKeyException exception) {
                log.atInfo().addKeyValue("roleCode", role.code()).log("Built-in role already present");
            }
        }
    }

    private void seedUsers() {
        List<SecurityProperties.SeedUser> configured = securityProperties.getSeedUsers();
        if (configured == null || configured.isEmpty()) {
            log.atInfo().log("No seed users declared under rcdis.security.seed-users; skipping user seeding");
            return;
        }
        for (SecurityProperties.SeedUser seedUser : configured) {
            try {
                seedUser(seedUser);
            } catch (RuntimeException exception) {
                // One unusable entry must not stop the application from seeding the remaining ones.
                log.atError()
                        .setCause(exception)
                        .addKeyValue("username", seedUser.getUsername())
                        .log("Failed to seed user from application.yml");
            }
        }
    }

    private void seedUser(SecurityProperties.SeedUser seedUser) {
        if (!StringUtils.hasText(seedUser.getUsername()) || !StringUtils.hasText(seedUser.getPassword())) {
            log.atWarn().log("Skipping seed user without a username or password");
            return;
        }
        String username = seedUser.getUsername().trim();
        if (findByUsername(username) != null) {
            // Respect an existing account; a password rotated through /api/users must win.
            return;
        }
        List<Long> roleIds = resolveRoleIds(seedUser.getRoles());
        if (roleIds.isEmpty()) {
            log.atWarn()
                    .addKeyValue("username", username)
                    .log("Skipping seed user with no valid roles");
            return;
        }
        SysUserEntity entity = new SysUserEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordEncoder.encode(seedUser.getPassword()));
        entity.setDisplayName(StringUtils.hasText(seedUser.getDisplayName())
                ? seedUser.getDisplayName().trim()
                : username);
        entity.setTenantId(StringUtils.hasText(seedUser.getTenantId())
                ? seedUser.getTenantId().trim()
                : DEFAULT_TENANT_ID);
        entity.setStatus(STATUS_ACTIVE);
        entity.setVersion(FLAG_FALSE);
        try {
            sysUserMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            log.atInfo().addKeyValue("username", username).log("Seed user already present");
            return;
        }
        for (Long roleId : roleIds) {
            SysUserRoleEntity link = new SysUserRoleEntity();
            link.setUserId(entity.getId());
            link.setRoleId(roleId);
            try {
                sysUserRoleMapper.insert(link);
            } catch (DuplicateKeyException exception) {
                log.atInfo()
                        .addKeyValue("username", username)
                        .addKeyValue("roleId", roleId)
                        .log("Seed user role link already present");
            }
        }
        log.atInfo()
                .addKeyValue("username", username)
                .addKeyValue("roleCount", roleIds.size())
                .log("Seed user created");
    }

    private List<Long> resolveRoleIds(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        List<Long> roleIds = new ArrayList<>();
        for (String code : roleCodes) {
            if (!StringUtils.hasText(code)) {
                continue;
            }
            SysRoleEntity role = findRoleByCode(code.trim());
            if (role != null) {
                roleIds.add(role.getId());
            } else {
                log.atWarn().addKeyValue("roleCode", code).log("Seed user references an unknown role; ignored");
            }
        }
        return roleIds;
    }

    private SysRoleEntity findRoleByCode(String code) {
        return sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRoleEntity>()
                .eq(SysRoleEntity::getCode, code)
                .eq(SysRoleEntity::getDeleted, FLAG_FALSE)
                .orderByAsc(SysRoleEntity::getId)
                .last("limit 1"));
    }

    private SysUserEntity findByUsername(String username) {
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getUsername, username)
                .eq(SysUserEntity::getDeleted, FLAG_FALSE)
                .orderByAsc(SysUserEntity::getId)
                .last("limit 1"));
    }

    private record BuiltinRole(String code, String name, String description) {
    }
}
