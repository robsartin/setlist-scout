package com.robsartin.setlistscout;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Single-user gate: anyone can hit "Sign in with Google", but only the allow-listed
 * email actually gets past the check. Everyone else is rejected during the OIDC
 * user-info exchange, before any session is established.
 *
 * Google login is an OIDC flow (the "openid" scope is requested in application.yml),
 * so the gate has to hook the OIDC user service -- a plain OAuth2UserService is never
 * invoked when an id_token is present, which would let the allow-list be bypassed.
 */
@Configuration
public class SecurityConfig {

    private final AppProperties appProperties;

    public SecurityConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/error").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(allowListedOidcUserService()))
            );
        return http.build();
    }

    private OAuth2UserService<OidcUserRequest, OidcUser> allowListedOidcUserService() {
        OidcUserService delegate = new OidcUserService();
        java.util.List<String> allowedEmails = appProperties.auth().allowedEmails();

        return (userRequest) -> {
            OidcUser user = delegate.loadUser(userRequest);
            String email = user.getEmail();
            Boolean emailVerified = user.getEmailVerified();

            boolean allowed = email != null
                    && emailVerified != null && emailVerified
                    && allowedEmails.stream().anyMatch(e -> e.equalsIgnoreCase(email));
            if (!allowed) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("not_authorized"),
                        "This account is not authorized to use this application."
                );
            }
            return user;
        };
    }
}
