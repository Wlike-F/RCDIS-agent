package com.rcdis.agent.common.util;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.util.StringUtils;

public final class CsvUtils {

    private CsvUtils() {
        throw new UnsupportedOperationException("CsvUtils cannot be instantiated");
    }

    public static CsvTable parseUtf8(byte[] csvBytes) {
        Objects.requireNonNull(csvBytes, "csvBytes must not be null");
        if (csvBytes.length == 0) {
            throw new IllegalArgumentException("CSV content must not be empty");
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(csvBytes), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (!StringUtils.hasText(headerLine)) {
                throw new IllegalArgumentException("CSV header must not be blank");
            }
            List<String> headers = normalizeHeaders(parseLine(headerLine, 1));
            List<Map<String, String>> rows = readRows(reader, headers);
            return new CsvTable(headers, rows);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse CSV content", exception);
        }
    }

    private static List<Map<String, String>> readRows(BufferedReader reader, List<String> headers) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        String line = reader.readLine();
        int rowNumber = 2;
        while (line != null) {
            if (StringUtils.hasText(line)) {
                rows.add(toRow(headers, parseLine(line, rowNumber), rowNumber));
            }
            line = reader.readLine();
            rowNumber++;
        }
        return rows;
    }

    private static Map<String, String> toRow(List<String> headers, List<String> values, int rowNumber) {
        if (values.size() > headers.size()) {
            throw new IllegalArgumentException("CSV row has more columns than header, rowNumber=" + rowNumber);
        }
        Map<String, String> row = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            String value = "";
            if (index < values.size()) {
                value = values.get(index).trim();
            }
            row.put(headers.get(index), value);
        }
        return row;
    }

    private static List<String> normalizeHeaders(List<String> rawHeaders) {
        List<String> headers = rawHeaders.stream()
                .map(String::trim)
                .toList();
        Set<String> uniqueHeaders = new HashSet<>();
        for (String header : headers) {
            if (!StringUtils.hasText(header)) {
                throw new IllegalArgumentException("CSV header name must not be blank");
            }
            if (!uniqueHeaders.add(header)) {
                throw new IllegalArgumentException("CSV header name is duplicated: " + header);
            }
        }
        return headers;
    }

    private static List<String> parseLine(String line, int rowNumber) {
        List<String> values = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder currentValue = new StringBuilder();
        for (int index = 0; index < line.length(); index++) {
            char currentChar = line.charAt(index);
            if (currentChar == '"') {
                if (inQuotes && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    currentValue.append('"');
                    index++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (currentChar == ',' && !inQuotes) {
                values.add(currentValue.toString());
                currentValue.setLength(0);
            } else {
                currentValue.append(currentChar);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("CSV row has an unclosed quote, rowNumber=" + rowNumber);
        }
        values.add(currentValue.toString());
        return values;
    }
}
