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
    private DevUser devUser = new DevUser();

    @Getter
    @Setter
    public static class Jwt {

        private String issuer;
        private String secret;
        private long accessTokenTtlMinutes;
    }

    @Getter
    @Setter
    public static class DevUser {

        private boolean enabled;
        private String userId;
        private String username;
        private String password;
        private String tenantId;
        private List<String> roles = new ArrayList<>();
    }
}
