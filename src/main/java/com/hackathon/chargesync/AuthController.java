package com.hackathon.chargesync;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final OAuthProviderRegistry oauthProviders;
    public AuthController(AuthService auth, OAuthProviderRegistry oauthProviders) {
        this.auth = auth;
        this.oauthProviders = oauthProviders;
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpSession session) {
        return auth.sessionView(auth.current(session));
    }

    @GetMapping("/providers")
    public Map<String, Object> providers() {
        return Map.of("providers", oauthProviders.providerIds());
    }

    @PostMapping("/oauth/start")
    public Map<String, String> startOAuth(@RequestBody OAuthStartRequest body, HttpServletRequest request) {
        String provider = body.provider == null ? "" : body.provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (!oauthProviders.isConfigured(provider)) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND, "This sign-in provider is not configured on this server.");
        String role = body.role == null ? "" : body.role.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("DRIVER", "STATION").contains(role)) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST, "Choose a driver or station account.");

        PortalService.StationRequest station = null;
        if ("STATION".equals(role) && Boolean.TRUE.equals(body.registering)) {
            if (body.stationName == null || body.stationName.isBlank() || body.stationArea == null || body.stationArea.isBlank()) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Enter your station name and area.");
            }
            station = new PortalService.StationRequest();
            station.name = body.stationName.trim();
            station.area = body.stationArea.trim();
            station.latitude = body.latitude == null ? 12.0 : body.latitude;
            station.longitude = body.longitude == null ? 77.0 : body.longitude;
            station.maxOutputKw = body.maxOutputKw == null ? 60 : body.maxOutputKw;
            station.pricePerKwh = body.pricePerKwh == null ? 18 : body.pricePerKwh;
            station.docks = body.docks;
        }

        HttpSession session = request.getSession(true);
        session.setAttribute(OAuthLoginSuccessHandler.ROLE_SESSION_KEY, role);
        session.setAttribute(OAuthLoginSuccessHandler.REGISTERING_SESSION_KEY, Boolean.TRUE.equals(body.registering));
        if (station != null) session.setAttribute(OAuthLoginSuccessHandler.STATION_SESSION_KEY, station);
        else session.removeAttribute(OAuthLoginSuccessHandler.STATION_SESSION_KEY);
        return Map.of("authorizationUrl", "/oauth2/authorization/" + provider);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        AuthService.Account account = auth.authenticate(body.email, body.password, body.role);
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(AuthService.SESSION_ACCOUNT_ID, account.id);
        return auth.sessionView(account);
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegistrationRequest body, HttpServletRequest request) {
        PortalService.StationRequest station = null;
        if ("STATION".equalsIgnoreCase(body.role)) {
            station = new PortalService.StationRequest();
            station.name = body.stationName;
            station.area = body.stationArea;
            station.latitude = body.latitude == null ? 12.0 : body.latitude;
            station.longitude = body.longitude == null ? 77.0 : body.longitude;
            station.maxOutputKw = body.maxOutputKw == null ? 60 : body.maxOutputKw;
            station.pricePerKwh = body.pricePerKwh == null ? 18 : body.pricePerKwh;
            station.docks = body.docks;
        }
        AuthService.Account account = auth.register(body.email, body.password, body.role, station);
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(AuthService.SESSION_ACCOUNT_ID, account.id);
        return auth.sessionView(account);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        if (session != null) session.invalidate();
        return Map.of("authenticated", false);
    }

    public static class LoginRequest { public String email; public String password; public String role; }
    public static class OAuthStartRequest {
        public String provider; public String role; public Boolean registering;
        public String stationName; public String stationArea;
        public Double latitude; public Double longitude; public Double maxOutputKw; public Double pricePerKwh;
        public java.util.List<PortalService.DockRequest> docks;
    }
    public static class RegistrationRequest {
        public String email; public String password; public String role;
        public String stationName; public String stationArea;
        public Double latitude; public Double longitude; public Double maxOutputKw; public Double pricePerKwh;
        public java.util.List<PortalService.DockRequest> docks;
    }
}
