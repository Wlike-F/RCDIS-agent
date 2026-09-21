package com.rcdis.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * A built-in authorization role backed by the {@code sys_role} table.
 *
 * <p>{@code code} (one of {@code ADMIN}, {@code APPROVER}, {@code RESEARCHER}) is the stable
 * identifier carried in the JWT roles claim and matched by Spring Security {@code hasRole(...)}.</p>
 */
@Getter
@Setter
@TableName("sys_role")
public class SysRoleEntity extends BaseEntity {

    private String code;
    private String name;
    private String description;
    private String status;

    @Version
    private Integer version;
}
