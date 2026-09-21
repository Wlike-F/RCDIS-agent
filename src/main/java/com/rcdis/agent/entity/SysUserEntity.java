package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * A real login account backed by the {@code sys_user} table.
 *
 * <p>{@code passwordHash} stores a BCrypt digest; the plaintext password is never persisted. Roles
 * are resolved through {@code sys_user_role} at login time and carried in the JWT roles claim.</p>
 */
@Getter
@Setter
@TableName("sys_user")
public class SysUserEntity extends BaseEntity {

    private String username;
    private String passwordHash;
    private String displayName;
    private String tenantId;
    /** {@code ACTIVE} or {@code DISABLED}. A disabled account cannot log in. */
    private String status;
    private OffsetDateTime lastLoginAt;

    @Version
    private Integer version;
}
