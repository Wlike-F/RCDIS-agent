package com.rcdis.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.rcdis.agent.common.security.JwtAuthenticationFilter;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfiguration {

    private final SecurityProperties securityProperties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> {
                    // SSE completion runs as an ASYNC dispatch on a fresh Tomcat thread, where the
                    // JWT filter (shouldNotFilterAsyncDispatch=true by default) does NOT repopulate
                    // the SecurityContext. Without permitting the ASYNC dispatcher type,
                    // AuthorizationFilter denies it and, because the response is already committed,
                    // the connection never closes and the browser stream reader hangs forever.
                    // The initial REQUEST dispatch is still fully authenticated below.
                    registry.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll();

                    // Public endpoints: login, the Feishu callback (authenticated by X-Lark-Signature
                    // plus the verification token instead of a JWT), health and API docs.
                    registry.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll();
                    registry.requestMatchers(HttpMethod.POST, "/api/feishu/card-callback").permitAll();
                    registry.requestMatchers("/api/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    registry.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    registry.requestMatchers("/actuator/prometheus").hasRole("ADMIN");

                    // Role rules are enforced here at the filter layer and ALWAYS apply, independent of
                    // authRequired, so privileged resources stay protected even when the global
                    // authenticated() switch below is relaxed for local development. The method-level
                    // @PreAuthorize annotations on the controllers mirror these rules to document intent.
                    registry.requestMatchers("/api/model-providers/**").hasRole("ADMIN");
                    registry.requestMatchers("/api/admin/**").hasRole("ADMIN");
                    registry.requestMatchers("/api/users/**").hasRole("ADMIN");
                    registry.requestMatchers("/api/feishu/**").hasRole("ADMIN");
                    registry.requestMatchers("/api/developer/**").hasRole("ADMIN");
                    registry.requestMatchers("/api/audit-logs/**").hasAnyRole("ADMIN", "APPROVER");
                    registry.requestMatchers("/api/reimbursements/*/approve", "/api/reimbursements/*/reject")
                            .hasAnyRole("ADMIN", "APPROVER");
                    registry.requestMatchers(HttpMethod.POST, "/api/projects").hasRole("ADMIN");
                    registry.requestMatchers(HttpMethod.PUT, "/api/projects/*").hasRole("ADMIN");
                    registry.requestMatchers(HttpMethod.DELETE, "/api/projects/*").hasRole("ADMIN");
                    registry.requestMatchers(HttpMethod.POST, "/api/projects/*/budget-categories").hasRole("ADMIN");
                    registry.requestMatchers(HttpMethod.PUT, "/api/projects/*/budget-categories/*").hasRole("ADMIN");
                    registry.requestMatchers(HttpMethod.DELETE, "/api/projects/*/budget-categories/*").hasRole("ADMIN");

                    // Everything else under /api (project/expense/reimbursement reads and writes, chat,
                    // files) is open to any authenticated role and follows the global switch.
                    if (securityProperties.isAuthRequired()) {
                        registry.requestMatchers("/api/**").authenticated();
                    } else {
                        registry.requestMatchers("/api/**").permitAll();
                    }
                    registry.anyRequest().denyAll();
                })
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Form login user store is not enabled: " + username);
        };
    }
}
