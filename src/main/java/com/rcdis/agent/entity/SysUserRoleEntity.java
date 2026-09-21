package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Join table assigning a role to a user, backed by {@code sys_user_role}.
 *
 * <p>Deliberately lightweight: it carries no soft-delete or version columns, so an assignment is
 * removed by a hard delete when a user's roles are reassigned. It does not extend {@link BaseEntity}
 * because MyBatis-Plus would otherwise append a {@code deleted = 0} predicate to a table that has no
 * such column.</p>
 */
@Getter
@Setter
@TableName("sys_user_role")
public class SysUserRoleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long roleId;
    private OffsetDateTime createdAt;
    private String createdBy;
}
