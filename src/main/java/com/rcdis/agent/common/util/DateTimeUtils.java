package com.rcdis.agent.common.util;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Objects;

public final class DateTimeUtils {

    public static final ZoneId DEFAULT_ZONE_ID = ZoneId.of("Asia/Shanghai");

    private DateTimeUtils() {
        throw new UnsupportedOperationException("DateTimeUtils cannot be instantiated");
    }

    public static LocalDate today(Clock clock, ZoneId zoneId) {
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        return LocalDate.now(clock.withZone(zoneId));
    }

    public static OffsetDateTime now(Clock clock, ZoneId zoneId) {
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        return OffsetDateTime.now(clock.withZone(zoneId));
    }

    public static DateRange dayRange(LocalDate date, ZoneId zoneId) {
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        OffsetDateTime start = date.atStartOfDay(zoneId).toOffsetDateTime();
        OffsetDateTime end = date.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime();
        return new DateRange(start, end);
    }

    public static DateRange monthRange(YearMonth month, ZoneId zoneId) {
        Objects.requireNonNull(month, "month must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        OffsetDateTime start = month.atDay(1).atStartOfDay(zoneId).toOffsetDateTime();
        OffsetDateTime end = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toOffsetDateTime();
        return new DateRange(start, end);
    }

    public static DateRange yearRange(int year, ZoneId zoneId) {
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        OffsetDateTime start = LocalDate.of(year, 1, 1).atStartOfDay(zoneId).toOffsetDateTime();
        OffsetDateTime end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zoneId).toOffsetDateTime();
        return new DateRange(start, end);
    }
}
