package com.hackathon.chargesync;

import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;

import java.util.*;

/** Creates only provider registrations that have credentials in the server environment. */
@Component
public class OAuthProviderRegistry implements ClientRegistrationRepository {
    private final Map<String, ClientRegistration> registrations;
    private final String publicBaseUrl;

    public OAuthProviderRegistry(Environment environment) {
        String publicUrl = environment.getProperty("CHARGESYNC_PUBLIC_BASE_URL");
        if (blank(publicUrl)) publicUrl = environment.getProperty("RENDER_EXTERNAL_URL", "http://localhost:8082");
        this.publicBaseUrl = trimTrailingSlash(publicUrl);
        Map<String, ClientRegistration> configured = new LinkedHashMap<>();
        addGoogle(configured, environment);
        addMicrosoft(configured, environment);
        addApple(configured, environment);
        this.registrations = Collections.unmodifiableMap(configured);
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        return registrations.get(registrationId);
    }

    public boolean isConfigured(String registrationId) {
        return registrations.containsKey(registrationId);
    }

    public List<String> providerIds() {
        return List.copyOf(registrations.keySet());
    }

    public String redirectUri(String registrationId) {
        return publicBaseUrl + "/login/oauth2/code/" + registrationId;
    }

    private void addGoogle(Map<String, ClientRegistration> configured, Environment env) {
        String id = env.getProperty("GOOGLE_CLIENT_ID", "");
        String secret = env.getProperty("GOOGLE_CLIENT_SECRET", "");
        if (blank(id) || blank(secret)) return;
        ClientRegistration registration = base("google", id, secret, "Google", ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .scope("openid", "profile", "email")
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .tokenUri("https://oauth2.googleapis.com/token")
            .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
            .userNameAttributeName("sub")
            .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
            .issuerUri("https://accounts.google.com")
            .build();
        configured.put("google", registration);
    }

    private void addMicrosoft(Map<String, ClientRegistration> configured, Environment env) {
        String id = env.getProperty("MICROSOFT_CLIENT_ID", "");
        String secret = env.getProperty("MICROSOFT_CLIENT_SECRET", "");
        String tenant = env.getProperty("MICROSOFT_TENANT_ID", "").trim();
        if (blank(id) || blank(secret) || !tenant.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return;
        String authority = "https://login.microsoftonline.com/" + tenant;
        ClientRegistration registration = base("microsoft", id, secret, "Microsoft", ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .scope("openid", "profile", "email")
            .authorizationUri(authority + "/oauth2/v2.0/authorize")
            .tokenUri(authority + "/oauth2/v2.0/token")
            .userInfoUri("https://graph.microsoft.com/oidc/userinfo")
            .userNameAttributeName("sub")
            .jwkSetUri(authority + "/discovery/v2.0/keys")
            .issuerUri(authority + "/v2.0")
            .build();
        configured.put("microsoft", registration);
    }

    private void addApple(Map<String, ClientRegistration> configured, Environment env) {
        String id = env.getProperty("APPLE_CLIENT_ID", "");
        String secret = env.getProperty("APPLE_CLIENT_SECRET", "");
        boolean appleProfile = Arrays.asList(env.getActiveProfiles()).contains("apple");
        if (blank(id) || blank(secret) || !publicBaseUrl.startsWith("https://") || !appleProfile) return;
        ClientRegistration registration = base("apple", id, secret, "Apple", ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .scope("openid", "email")
            .authorizationUri("https://appleid.apple.com/auth/authorize?response_mode=form_post")
            .tokenUri("https://appleid.apple.com/auth/token")
            .userNameAttributeName("sub")
            .jwkSetUri("https://appleid.apple.com/auth/keys")
            .issuerUri("https://appleid.apple.com")
            .build();
        configured.put("apple", registration);
    }

    private ClientRegistration.Builder base(String registrationId, String clientId, String clientSecret,
            String clientName, ClientAuthenticationMethod authMethod) {
        return ClientRegistration.withRegistrationId(registrationId)
            .clientId(clientId)
            .clientSecret(clientSecret)
            .clientAuthenticationMethod(authMethod)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(publicBaseUrl + "/login/oauth2/code/{registrationId}")
            .clientName(clientName);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }
}
