package com.rcdis.agent.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class CsvUtilsTests {

    @Test
    void parseUtf8HandlesQuotedComma() {
        byte[] bytes = "project,amount,description\nNSFC-1,42.00,\"reagent, grade A\"".getBytes(StandardCharsets.UTF_8);

        CsvTable table = CsvUtils.parseUtf8(bytes);

        assertThat(table.headers()).containsExactly("project", "amount", "description");
        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().get(0).get("description")).isEqualTo("reagent, grade A");
    }

    @Test
    void parseUtf8RejectsDuplicatedHeaders() {
        byte[] bytes = "project,project\nA,B".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CsvUtils.parseUtf8(bytes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicated");
    }
}
