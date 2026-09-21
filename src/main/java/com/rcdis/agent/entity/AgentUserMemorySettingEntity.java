package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Per-user switches for cross-session semantic memory, read (inject) and write (extract) separated.
 * Combined with the global config switches to decide effective behaviour.
 */
@Getter
@Setter
@TableName("agent_user_memory_setting")
public class AgentUserMemorySettingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private Boolean extractEnabled;
    private Boolean injectEnabled;

    @TableField(value = "updated_at")
    private OffsetDateTime updatedAt;
}
