package com.rcdis.agent.to;

import java.util.Map;

/**
 * What to answer a Feishu card callback with.
 *
 * <p>Returning the replacement card inside the callback response updates the card in place for free.
 * The alternative, calling the card update API with {@code event.token}, is limited to 30 minutes
 * and two updates per card, so it is deliberately not used.</p>
 *
 * @param card the replacement card as card JSON 2.0, or null to keep the current card unchanged
 */
public record CardActionOutcomeTO(
        String toastType,
        String toastContent,
        Map<String, Object> card,
        boolean executed,
        boolean duplicate
) {

    public static final String TOAST_SUCCESS = "success";
    public static final String TOAST_ERROR = "error";
    public static final String TOAST_WARNING = "warning";
    public static final String TOAST_INFO = "info";

    public static CardActionOutcomeTO toast(String toastType, String toastContent) {
        return new CardActionOutcomeTO(toastType, toastContent, null, false, false);
    }

    public static CardActionOutcomeTO decided(String toastContent, Map<String, Object> card) {
        return new CardActionOutcomeTO(TOAST_SUCCESS, toastContent, card, true, false);
    }

    public static CardActionOutcomeTO duplicate(String toastContent) {
        return new CardActionOutcomeTO(TOAST_INFO, toastContent, null, false, true);
    }
}
