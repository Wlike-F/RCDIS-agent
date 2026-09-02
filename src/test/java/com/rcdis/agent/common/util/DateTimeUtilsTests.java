package com.rcdis.agent.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class DateTimeUtilsTests {

    @Test
    void dayRangeUsesHalfOpenShanghaiRange() {
        DateRange range = DateTimeUtils.dayRange(LocalDate.of(2026, 8, 29), DateTimeUtils.DEFAULT_ZONE_ID);

        assertThat(range.startInclusive().toString()).isEqualTo("2026-08-29T00:00+08:00");
        assertThat(range.endExclusive().toString()).isEqualTo("2026-08-30T00:00+08:00");
    }
}
