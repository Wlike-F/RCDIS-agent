package com.rcdis.agent.entity;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * A model exposed by a {@link ModelProviderEntity}. At most one row per provider is the default.
 */
@Getter
@Setter
@TableName("model_provider_model")
public class ModelProviderModelEntity extends BaseEntity {

    private Long providerId;
    /** Wire model name sent in the request body, e.g. {@code qwen-plus}. */
    private String modelName;
    private String displayName;
    /** What the model can consume: TEXT, VISION or EMBEDDING. Drives automatic OCR routing. */
    private String capability;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Integer isDefault;
    /** {@code MANUAL} when entered by a user, {@code DISCOVERED} when pulled from the models endpoint. */
    private String source;
    private String status;
    private String remark;

    @Version
    private Integer version;
}
