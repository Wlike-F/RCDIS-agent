package com.rcdis.agent.service.impl;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rcdis.agent.entity.AppSettingEntity;
import com.rcdis.agent.mapper.AppSettingMapper;
import com.rcdis.agent.service.AppSettingService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AppSettingServiceImpl implements AppSettingService {

    private final AppSettingMapper appSettingMapper;

    @Override
    public String get(String key) {
        AppSettingEntity row = appSettingMapper.selectOne(new LambdaQueryWrapper<AppSettingEntity>()
                .eq(AppSettingEntity::getSettingKey, key)
                .last("LIMIT 1"));
        return row == null ? null : row.getSettingValue();
    }

    @Override
    public void put(String key, String value) {
        AppSettingEntity row = appSettingMapper.selectOne(new LambdaQueryWrapper<AppSettingEntity>()
                .eq(AppSettingEntity::getSettingKey, key)
                .last("LIMIT 1"));
        if (row == null) {
            row = new AppSettingEntity();
            row.setSettingKey(key);
            row.setSettingValue(value);
            row.setUpdatedAt(OffsetDateTime.now());
            appSettingMapper.insert(row);
        } else {
            row.setSettingValue(value);
            row.setUpdatedAt(OffsetDateTime.now());
            appSettingMapper.updateById(row);
        }
    }
}
