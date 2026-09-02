package com.rcdis.agent.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import org.springframework.util.StringUtils;

public final class MoneyUtils {

    public static final int MONEY_SCALE = 2;
    public static final RoundingMode MONEY_ROUNDING_MODE = RoundingMode.HALF_UP;

    private MoneyUtils() {
        throw new UnsupportedOperationException("MoneyUtils cannot be instantiated");
    }

    public static BigDecimal parse(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Money value must not be blank");
        }
        try {
            return normalize(new BigDecimal(value.trim()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Money value is not a valid decimal: " + value, exception);
        }
    }

    public static BigDecimal normalize(BigDecimal amount) {
        return requireAmount(amount, "amount").setScale(MONEY_SCALE, MONEY_ROUNDING_MODE);
    }

    public static BigDecimal add(BigDecimal left, BigDecimal right) {
        return normalize(requireAmount(left, "left").add(requireAmount(right, "right")));
    }

    public static BigDecimal subtract(BigDecimal left, BigDecimal right) {
        return normalize(requireAmount(left, "left").subtract(requireAmount(right, "right")));
    }

    public static boolean equalByValue(BigDecimal left, BigDecimal right) {
        return compare(left, right) == 0;
    }

    public static boolean equalAtCent(BigDecimal left, BigDecimal right) {
        return normalize(left).compareTo(normalize(right)) == 0;
    }

    public static boolean greaterThan(BigDecimal left, BigDecimal right) {
        return compare(left, right) > 0;
    }

    public static boolean greaterThanOrEqual(BigDecimal left, BigDecimal right) {
        return compare(left, right) >= 0;
    }

    public static boolean lessThan(BigDecimal left, BigDecimal right) {
        return compare(left, right) < 0;
    }

    public static boolean lessThanOrEqual(BigDecimal left, BigDecimal right) {
        return compare(left, right) <= 0;
    }

    public static int compare(BigDecimal left, BigDecimal right) {
        return requireAmount(left, "left").compareTo(requireAmount(right, "right"));
    }

    public static BigDecimal requireNonNegative(BigDecimal amount, String fieldName) {
        BigDecimal checkedAmount = requireAmount(amount, fieldName);
        if (checkedAmount.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return checkedAmount;
    }

    private static BigDecimal requireAmount(BigDecimal amount, String fieldName) {
        return Objects.requireNonNull(amount, fieldName + " must not be null");
    }
}
