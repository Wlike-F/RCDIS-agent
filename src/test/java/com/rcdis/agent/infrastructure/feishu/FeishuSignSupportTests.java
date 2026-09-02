package com.rcdis.agent.infrastructure.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeishuSignSupportTests {

    @Test
    void signReturnsDeterministicBase64Hmac() {
        String signature = FeishuSignSupport.sign("1234567890", "test-secret");

        assertThat(signature).isEqualTo("qCaOcLimil1ehZl6GzN2CUL6wgdt4onZPxvw8V+3TzA=");
    }
}
