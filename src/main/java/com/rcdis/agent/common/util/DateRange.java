package com.rcdis.agent.common.util;

import java.time.OffsetDateTime;
import java.util.Objects;

public record DateRange(
        OffsetDateTime startInclusive,
        OffsetDateTime endExclusive
) {

    public DateRange {
        Objects.requireNonNull(startInclusive, "startInclusive must not be null");
        Objects.requireNonNull(endExclusive, "endExclusive must not be null");
        if (!startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("startInclusive must be before endExclusive");
        }
    }
}
