package com.rcdis.agent.common.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Approximate token counting for context-window budgeting.
 *
 * <p>Uses jtokkit's cl100k_base (already on the classpath via spring-ai) as a model-agnostic
 * estimate. It is intentionally conservative: callers add head-room because real models (esp. for
 * Chinese) may tokenize differently. Never use naive chars/4 for CJK text.</p>
 */
public final class TokenEstimator {

    private static final Encoding ENCODING =
            Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private TokenEstimator() {
        throw new UnsupportedOperationException("TokenEstimator cannot be instantiated");
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return ENCODING.countTokens(text);
        } catch (RuntimeException exception) {
            // Fall back to a conservative char-based estimate if tokenization fails.
            return (int) Math.ceil(text.length() / 2.0);
        }
    }
}
