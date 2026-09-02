package com.rcdis.agent.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MoneyUtilsTests {

    @Test
    void parseNormalizesToCentScale() {
        BigDecimal amount = MoneyUtils.parse("13.805");

        assertThat(amount).isEqualByComparingTo("13.81");
        assertThat(amount.scale()).isEqualTo(2);
    }

    @Test
    void compareIgnoresBigDecimalScale() {
        boolean equals = MoneyUtils.equalByValue(new BigDecimal("42.0"), new BigDecimal("42.00"));

        assertThat(equals).isTrue();
    }

    @Test
    void parseRejectsInvalidText() {
        assertThatThrownBy(() -> MoneyUtils.parse("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Money value is not a valid decimal");
    }
}
