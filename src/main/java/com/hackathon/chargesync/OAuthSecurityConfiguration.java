package com.hackathon.chargesync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class OAuthSecurityConfiguration {
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${chargesync.cors.allowed-origins:https://localhost,capacitor://localhost}")
            java.util.List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(java.util.List.of("Content-Type", "Accept", "Authorization"));
        cors.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    SecurityFilterChain chargeSyncSecurity(HttpSecurity http, OAuthProviderRegistry providers,
            OAuthLoginSuccessHandler oauthHandler) throws Exception {
        http
            .cors(Customizer.withDefaults())
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
