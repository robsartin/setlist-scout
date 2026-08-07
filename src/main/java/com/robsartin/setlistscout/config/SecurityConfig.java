package com.robsartin.setlistscout.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Single-user gate: anyone can hit "Sign in with Google", but only the allow-listed
 * email actually gets past the check. Everyone else is rejected during the OAuth2
 * user-info exchange, before any session is established.
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
                .userInfoEndpoint(userInfo -> userInfo.oauth2UserService(allowListedUserService()))
            );
        return http.build();
    }

    private OAuth2UserService<OAuth2UserRequest, OAuth2User> allowListedUserService() {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        String allowedEmail = appProperties.auth().allowedEmail();

        return (userRequest) -> {
            OAuth2User user = delegate.loadUser(userRequest);
            String email = user.getAttribute("email");
            Boolean emailVerified = user.getAttribute("email_verified");

            if (email == null
                    || !email.equalsIgnoreCase(allowedEmail)
                    || emailVerified == null
                    || !emailVerified) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("not_authorized"),
                        "This account is not authorized to use this application."
                );
            }
            return user;
        };
    }
}
