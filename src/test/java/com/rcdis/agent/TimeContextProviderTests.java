package com.rcdis.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.rcdis.agent.agent.TimeContextProvider;
import com.rcdis.agent.config.AgentProperties;

/**
 * Unit tests for the runtime current-time anchor injected into the Agent system prompt.
 *
 * <p>The anchor is what lets the model resolve relative time ("今天/本月/刚才"); these tests pin the
 * Beijing-time rendering and the UTC-to-zone conversion so a regression cannot silently drop the
 * clock again.</p>
 */
class TimeContextProviderTests {

    private TimeContextProvider providerWithZone(String zone) {
        AgentProperties properties = new AgentProperties();
        properties.setClockZone(zone);
        return new TimeContextProvider(properties);
    }

    @Test
    void anchorRendersBeijingDateWeekdayTimeAndOffset() {
        TimeContextProvider provider = providerWithZone("Asia/Shanghai");
        // 2026-09-24T02:15Z == 2026-09-24 10:15 Beijing, which is a Thursday.
        ZonedDateTime now = ZonedDateTime.ofInstant(
                Instant.parse("2026-09-24T02:15:00Z"), ZoneId.of("UTC"));

        String anchor = provider.currentTimeAnchor(now);

        assertThat(anchor).contains("当前时间");
        assertThat(anchor).contains("2026-09-24");
        assertThat(anchor).contains("星期四");
        assertThat(anchor).contains("10:15");
        assertThat(anchor).contains("Asia/Shanghai");
        assertThat(anchor).contains("UTC+08:00");
    }

    @Test
    void anchorConvertsUtcInstantIntoNextBeijingDay() {
        TimeContextProvider provider = providerWithZone("Asia/Shanghai");
        // 2026-09-23T17:00Z rolls over to 2026-09-24 01:00 in Beijing.
        ZonedDateTime now = ZonedDateTime.ofInstant(
                Instant.parse("2026-09-23T17:00:00Z"), ZoneId.of("UTC"));

        String anchor = provider.currentTimeAnchor(now);

        assertThat(anchor).contains("2026-09-24");
        assertThat(anchor).contains("01:00");
    }

    @Test
    void defaultClockZoneIsBeijing() {
        assertThat(new AgentProperties().getClockZone()).isEqualTo("Asia/Shanghai");
    }
}
