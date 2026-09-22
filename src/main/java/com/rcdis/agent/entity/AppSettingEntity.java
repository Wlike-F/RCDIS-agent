package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Generic runtime key/value setting persisted in PostgreSQL, so admin-configurable options
 * (e.g. the receipt OCR model) survive restarts without touching {@code application.yml}.
 */
@Getter
@Setter
@TableName("app_setting")
public class AppSettingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String settingKey;
    private String settingValue;
    private OffsetDateTime updatedAt;
}
