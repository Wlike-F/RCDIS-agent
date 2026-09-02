package com.rcdis.agent.infrastructure.feishu;

import java.nio.charset.StandardCharsets;
import com.rcdis.agent.common.util.HashUtils;

public final class FeishuSignSupport {

    private FeishuSignSupport() {
        throw new UnsupportedOperationException("FeishuSignSupport cannot be instantiated");
    }

    public static String sign(String timestamp, String secret) {
        String stringToSign = timestamp + "\n" + secret;
        return HashUtils.hmacSha256Base64(new byte[0], stringToSign.getBytes(StandardCharsets.UTF_8));
    }
}
