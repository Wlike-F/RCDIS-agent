package com.rcdis.agent.agent;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.rcdis.agent.config.AgentProperties;

import lombok.RequiredArgsConstructor;

/**
 * Builds the runtime "current time" anchor appended to the system prompt on every request.
 *
 * <p>The static {@code agent-system.st} is cached at startup and carries no clock, so without this
 * the model cannot resolve relative time ("今天/本月/刚才") and would echo stale record timestamps as
 * if they were now. The anchor is rendered in the configured business zone (default Beijing time)
 * and lives in the trusted system message, not in user data.</p>
 */
@Component
@RequiredArgsConstructor
public class TimeContextProvider {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm", Locale.CHINA);

    private static final String FALLBACK_ZONE = "Asia/Shanghai";

    private final AgentProperties agentProperties;

    /** Current-time anchor resolved against the system clock and the configured zone. */
    public String currentTimeAnchor() {
        return currentTimeAnchor(ZonedDateTime.now(zone()));
    }

    /** Deterministic overload for tests and callers that already hold a fixed instant. */
    public String currentTimeAnchor(ZonedDateTime now) {
        ZoneId zone = zone();
        ZonedDateTime inZone = now.withZoneSameInstant(zone);
        String body = """
                # 当前时间（运行时注入，权威基准）
                现在是 %1$s（%2$s，UTC%3$s）。
                - 这是唯一的“现在”基准。判断“今天/昨天/本周/本月/本季度/刚才/最近”等相对时间一律以此为准，禁止使用你训练数据里的日期。
                - 需要日期区间（如“本月汇总”“最近一周”，或登记支出日期）时，先据此换算成具体起止日期，再传给工具。
                - 数据库返回的时间戳若与此时区不同，按业务时区向用户解释，不要把过去的记录说成“刚才”。
                """
                .formatted(TIMESTAMP_FORMAT.format(inZone), zone.getId(), inZone.getOffset().toString());
        return "\n\n" + body;
    }

    private ZoneId zone() {
        String configured = agentProperties.getClockZone();
        return ZoneId.of(configured == null || configured.isBlank() ? FALLBACK_ZONE : configured);
    }
}
