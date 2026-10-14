package com.hackathon.chargesync;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

@Component
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    static final String ROLE_SESSION_KEY = "chargesync.oauth.role";
    static final String REGISTERING_SESSION_KEY = "chargesync.oauth.registering";
    static final String STATION_SESSION_KEY = "chargesync.oauth.station";

    private final AuthService auth;

    public OAuthLoginSuccessHandler(AuthService auth) { this.auth = auth; }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        HttpSession session = request.getSession(false);
        if (session == null || !(authentication instanceof OAuth2AuthenticationToken oauth)) {
            response.sendRedirect("/login.html?oauthError=failed");
            return;
        }

        Object requestedRole = session.getAttribute(ROLE_SESSION_KEY);
        Object registeringValue = session.getAttribute(REGISTERING_SESSION_KEY);
        Object stationValue = session.getAttribute(STATION_SESSION_KEY);
        session.removeAttribute(ROLE_SESSION_KEY);
        session.removeAttribute(REGISTERING_SESSION_KEY);
        session.removeAttribute(STATION_SESSION_KEY);
        if (requestedRole == null) {
            response.sendRedirect("/login.html?oauthError=choose-role");
            return;
        }

        try {
            OAuth2User principal = oauth.getPrincipal();
            Map<String, Object> claims = principal.getAttributes();
            String provider = oauth.getAuthorizedClientRegistrationId();
            String subject = stringClaim(claims, "sub");
            if (subject == null) subject = principal.getName();
            String email = firstClaim(claims, "email", "preferred_username", "upn");
            boolean emailVerified = verified(claims.get("email_verified"));
            PortalService.StationRequest station = stationValue instanceof PortalService.StationRequest s ? s : null;
            boolean registering = Boolean.TRUE.equals(registeringValue);
            AuthService.Account account = auth.authenticateSocial(provider, subject, email, emailVerified,
                requestedRole.toString(), station, registering);

            request.changeSessionId();
            session.setAttribute(AuthService.SESSION_ACCOUNT_ID, account.id);
            response.sendRedirect("STATION".equals(account.role) ? "/station.html" : "/");
        } catch (RuntimeException error) {
            response.sendRedirect("/login.html?oauthError=" + errorCode(error));
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            org.springframework.security.core.AuthenticationException exception) throws IOException, ServletException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ROLE_SESSION_KEY);
            session.removeAttribute(REGISTERING_SESSION_KEY);
            session.removeAttribute(STATION_SESSION_KEY);
        }
        response.sendRedirect("/login.html?oauthError=provider");
    }

    private static String firstClaim(Map<String, Object> claims, String... names) {
        for (String name : names) {
            String value = stringClaim(claims, name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String stringClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        return value == null ? null : value.toString().trim();
    }

    private static boolean verified(Object claim) {
        return Boolean.TRUE.equals(claim) || "true".equalsIgnoreCase(claim == null ? "" : claim.toString());
    }

    private static String errorCode(RuntimeException error) {
        if (error instanceof ResponseStatusException status && status.getStatusCode().value() == 403) return "role";
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("choose create an account")) return "unregistered";
        if (message.contains("confirm the email")) return "email";
        if (message.contains("valid email")) return "email-missing";
        if (message.contains("station details")) return "station-details";
        return "failed";
    }
}
