package com.hackathon.chargesync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class OAuthSecurityConfiguration {
    @Bean
    SecurityFilterChain chargeSyncSecurity(HttpSecurity http, OAuthProviderRegistry providers,
            OAuthLoginSuccessHandler oauthHandler) throws Exception {
        http
            // The demo's JSON endpoints use their own role-aware session checks. Apple returns via a
            // cross-site form POST; the OAuth state parameter remains validated by Spring Security.
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/login/oauth2/code/apple"))
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
            .oauth2Login(oauth -> oauth
                .clientRegistrationRepository((ClientRegistrationRepository) providers)
                .successHandler(oauthHandler)
                .failureHandler(oauthHandler));
        return http.build();
    }
}
