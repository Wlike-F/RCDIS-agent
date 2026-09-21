package com.rcdis.agent.infrastructure.feishu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.rcdis.agent.common.util.HashUtils;
import com.rcdis.agent.config.FeishuProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Signs the action value embedded in approval cards.
 *
 * <p>Feishu proves that a callback came from Feishu, but it does not guarantee that a button's
 * {@code value} survived untouched: the callback payload is assembled client side and can be edited
 * or replayed. Signing {@code reimbursementId + action + submittedAt} binds one token to one specific
 * order at one specific submission, so an approver cannot swap in a different order, cannot flip
 * approve into reject, and cannot reuse a token after the order is resubmitted.</p>
 */
@Slf4j
@Component
public class CardActionTokenSupport {

    /** Keeps card rendering working locally when no signing key is configured. */
    private static final String DEV_FALLBACK_KEY = "rcdis-agent-development-card-action-signing-key";

    private final String signingKey;

    public CardActionTokenSupport(FeishuProperties properties) {
        String configured = properties.getCardActionSigningKey();
        if (StringUtils.hasText(configured)) {
            this.signingKey = configured.trim();
        } else {
            this.signingKey = DEV_FALLBACK_KEY;
            log.atWarn().log("rcdis.feishu.card-action-signing-key is not set; approval card action tokens are "
                    + "signed with a development fallback key. Set RCDIS_FEISHU_CARD_ACTION_SIGNING_KEY before "
                    + "deploying.");
        }
    }

    public String sign(Long reimbursementId, String action, long submittedAtEpochSecond) {
        return HashUtils.hmacSha256Hex(canonical(reimbursementId, action, submittedAtEpochSecond), signingKey);
    }

    public boolean verify(Long reimbursementId, String action, long submittedAtEpochSecond, String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        String expected = sign(reimbursementId, action, submittedAtEpochSecond);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static String canonical(Long reimbursementId, String action, long submittedAtEpochSecond) {
        return reimbursementId + "|" + action + "|" + submittedAtEpochSecond;
    }
}
