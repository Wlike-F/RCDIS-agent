package com.rcdis.agent.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "rcdis.security")
public class SecurityProperties {

    private boolean authRequired;
    private Jwt jwt = new Jwt();
    /** Accounts inserted on first startup only; afterwards users are managed through /api/users. */
    private List<SeedUser> seedUsers = new ArrayList<>();

    @Getter
    @Setter
    public static class Jwt {

        private String issuer;
        private String secret;
        private long accessTokenTtlMinutes;
    }

    /**
     * A bootstrap account declared in {@code application.yml}. The plaintext password is hashed with
     * BCrypt by {@code UserSeeder} before it is stored, and is only used on first startup.
     */
    @Getter
    @Setter
    public static class SeedUser {

        private String username;
        private String password;
        private String displayName;
        private String tenantId;
        private List<String> roles = new ArrayList<>();
    }
}
