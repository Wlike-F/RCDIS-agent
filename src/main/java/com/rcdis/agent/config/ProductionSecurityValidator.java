package com.rcdis.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

/** Fails a production startup before insecure development credentials can become active. */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionSecurityValidator implements InitializingBean {

    private static final String DEVELOPMENT_JWT_SECRET =
            "rcdis-agent-local-development-secret-change-me";
    private static final String DEVELOPMENT_ADMIN_PASSWORD = "admin123";

    private final SecurityProperties securityProperties;
    private final ModelProviderProperties modelProviderProperties;

    @Override
    public void afterPropertiesSet() {
        if (!securityProperties.isAuthRequired()) {
            throw new IllegalStateException("Production profile requires rcdis.security.auth-required=true");
        }
        String jwtSecret = securityProperties.getJwt().getSecret();
        if (!StringUtils.hasText(jwtSecret)
                || jwtSecret.length() < 32
                || DEVELOPMENT_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "Production profile requires a non-default JWT secret of at least 32 characters");
        }
        if (!StringUtils.hasText(modelProviderProperties.getSecretKey())) {
            throw new IllegalStateException(
                    "Production profile requires RCDIS_PROVIDER_SECRET_KEY for provider key encryption");
        }
        boolean insecureSeed = securityProperties.getSeedUsers().stream()
                .anyMatch(user -> !StringUtils.hasText(user.getPassword())
                        || DEVELOPMENT_ADMIN_PASSWORD.equals(user.getPassword()));
        if (insecureSeed) {
            throw new IllegalStateException(
                    "Production profile cannot start with an empty or default seed-user password");
        }
    }
}
