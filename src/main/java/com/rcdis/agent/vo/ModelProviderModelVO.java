package com.rcdis.agent.vo;

import java.math.BigDecimal;

import com.rcdis.agent.entity.ModelProviderModelEntity;

public record ModelProviderModelVO(
        Long id,
        String modelName,
        String displayName,
        BigDecimal temperature,
        Integer maxTokens,
        boolean defaultModel,
        String source,
        boolean enabled,
        String capability,
        String remark,
        Integer version
) {

    public static ModelProviderModelVO fromEntity(ModelProviderModelEntity entity) {
        return new ModelProviderModelVO(
                entity.getId(),
                entity.getModelName(),
                entity.getDisplayName(),
                entity.getTemperature(),
                entity.getMaxTokens(),
                Integer.valueOf(1).equals(entity.getIsDefault()),
                entity.getSource(),
                !"DISABLED".equals(entity.getStatus()),
                entity.getCapability() == null ? "TEXT" : entity.getCapability(),
                entity.getRemark(),
                entity.getVersion());
    }
}
