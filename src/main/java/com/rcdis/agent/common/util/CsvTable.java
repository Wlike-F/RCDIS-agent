package com.rcdis.agent.common.util;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CsvTable(
        List<String> headers,
        List<Map<String, String>> rows
) {

    public CsvTable {
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(rows, "rows must not be null");
        headers = List.copyOf(headers);
        rows = rows.stream()
                .map(Map::copyOf)
                .toList();
    }
}
