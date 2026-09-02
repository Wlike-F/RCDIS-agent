package com.rcdis.agent.common.response;

import java.util.List;

import com.baomidou.mybatisplus.core.metadata.IPage;

public record PageResponse<T>(
        long current,
        long size,
        long total,
        long pages,
        List<T> records
) {

    public static <T> PageResponse<T> fromPage(IPage<T> page) {
        return new PageResponse<>(
                page.getCurrent(),
                page.getSize(),
                page.getTotal(),
                page.getPages(),
                List.copyOf(page.getRecords()));
    }
}
