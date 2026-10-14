package com.hackathon.chargesync;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import jakarta.servlet.http.HttpSession;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** Small role-aware account store for the local hackathon demo. */
@Service
public class AuthService {
    public static final String SESSION_ACCOUNT_ID = "chargesync.accountId";
    private static final int HASH_ITERATIONS = 160_000;
    private static final int HASH_BITS = 256;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, Account> accountsById = new HashMap<>();
    private final Map<String, String> socialIdentityAccounts = new HashMap<>();
    private final PortalService portal;

    public AuthService(PortalService portal) {
        this.portal = portal;
        addSeedAccount("driver@chargesync.demo", "Driver123!", "DRIVER", null);
        addSeedAccount("station@chargesync.demo", "Station123!", "STATION", "virtual");
    }

    public synchronized Account register(String emailInput, String password, String role, PortalService.StationRequest stationRequest) {
        String email = normalizeEmail(emailInput);
        if (password == null || password.length() < 8) throw badRequest("Password must contain at least 8 characters.");
        String normalizedRole = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DRIVER", "STATION").contains(normalizedRole)) throw badRequest("Choose a driver or station account.");
        if (accounts.containsKey(email)) throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists.");

        String stationId = null;
        if ("STATION".equals(normalizedRole)) {
            if (stationRequest == null) throw badRequest("Station details are required for a station account.");
            if (stationRequest.docks == null || stationRequest.docks.isEmpty()) {
                double dockKw = stationRequest.maxOutputKw > 0 ? stationRequest.maxOutputKw : 60;
                stationRequest.docks = new ArrayList<>(List.of(
                    new PortalService.DockRequest("DOCK-01", "CCS2", dockKw, "AVAILABLE"),
                    new PortalService.DockRequest("DOCK-02", "CCS2", dockKw, "AVAILABLE"),
                    new PortalService.DockRequest("DOCK-03", "CCS2", dockKw, "AVAILABLE")));
            }
            stationId = portal.createStation(stationRequest).id;
        }

        Account account = new Account();
        account.id = UUID.randomUUID().toString();
        account.email = email;
        account.role = normalizedRole;
        account.stationId = stationId;
        account.passwordHash = hashPassword(password);
        accounts.put(email, account);
        accountsById.put(account.id, account);
        return account;
    }

    public synchronized Account authenticateSocial(String provider, String subject, String emailInput,
            boolean emailVerified, String role, PortalService.StationRequest stationRequest, boolean registering) {
        String normalizedRole = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DRIVER", "STATION").contains(normalizedRole)) throw badRequest("Choose a driver or station account.");
        if (provider == null || subject == null || subject.isBlank() || subject.length() > 255) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The identity provider did not return a valid account identifier.");
        }
        String email = normalizeEmail(emailInput);
        String identityKey = provider.toLowerCase(Locale.ROOT) + ":" + subject;
        String linkedAccountId = socialIdentityAccounts.get(identityKey);
        if (linkedAccountId != null) {
            Account linked = accountsById.get(linkedAccountId);
            if (linked == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "This sign-in is no longer linked to a ChargeSync account.");
            if (!linked.role.equals(normalizedRole)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Choose the account type linked to this sign-in.");
            return linked;
        }

        Account sameEmail = accounts.get(email);
        if (sameEmail != null) {
            if (!sameEmail.role.equals(normalizedRole)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Choose the account type linked to this email.");
            if (!emailVerified) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "This provider did not confirm the email address, so it cannot be linked to the existing account.");
            socialIdentityAccounts.put(identityKey, sameEmail.id);
            return sameEmail;
        }
        if (!registering) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No account is linked to this sign-in yet. Choose Create an account to continue.");

        String stationId = null;
        if ("STATION".equals(normalizedRole)) {
            if (stationRequest == null) throw badRequest("Station details are required for a station account.");
            if (stationRequest.docks == null || stationRequest.docks.isEmpty()) {
                double dockKw = stationRequest.maxOutputKw > 0 ? stationRequest.maxOutputKw : 60;
                stationRequest.docks = new ArrayList<>(List.of(
                    new PortalService.DockRequest("DOCK-01", "CCS2", dockKw, "AVAILABLE"),
                    new PortalService.DockRequest("DOCK-02", "CCS2", dockKw, "AVAILABLE"),
                    new PortalService.DockRequest("DOCK-03", "CCS2", dockKw, "AVAILABLE")));
            }
            stationId = portal.createStation(stationRequest).id;
        }

        Account created = new Account();
        created.id = UUID.randomUUID().toString();
        created.email = email;
        created.role = normalizedRole;
        created.stationId = stationId;
        // Social accounts intentionally have no local password hash.
        accounts.put(email, created);
        accountsById.put(created.id, created);
        socialIdentityAccounts.put(identityKey, created.id);
        return created;
    }

    public synchronized Account authenticate(String emailInput, String password, String requestedRole) {
        String email = normalizeEmail(emailInput);
        Account account = accounts.get(email);
        String role = requestedRole == null ? "" : requestedRole.trim().toUpperCase(Locale.ROOT);
        if (account == null || !account.role.equals(role) || !verifyPassword(password, account.passwordHash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email, password, or account type is incorrect.");
        }
        return account;
    }

    public synchronized Account current(HttpSession session) {
        if (session == null) return null;
        Object id = session.getAttribute(SESSION_ACCOUNT_ID);
        if (id == null) return null;
        return accountsById.get(id.toString());
    }

    public Account requireAccount(HttpSession session) {
        Account account = current(session);
        if (account == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to continue.");
        return account;
    }

    public Account requireRole(HttpSession session, String role) {
        Account account = requireAccount(session);
        if (!role.equals(account.role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This action requires a " + role.toLowerCase(Locale.ROOT) + " account.");
        return account;
    }

    public void requireStationOwnership(Account account, String stationId) {
        if (!"STATION".equals(account.role) || !Objects.equals(account.stationId, stationId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A station account can only change its own station.");
        }
    }

    public synchronized void assignStation(Account account, String stationId) {
        account.stationId = stationId;
    }

    public Map<String, Object> sessionView(Account account) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("authenticated", account != null);
        if (account != null) {
            view.put("email", account.email);
            view.put("role", account.role);
            view.put("stationId", account.stationId);
        }
        return view;
    }

    public static class Account {
        public String id;
        public String email;
        public String role;
        public String stationId;
        private String passwordHash;
    }

    private void addSeedAccount(String email, String password, String role, String stationId) {
        Account account = new Account();
        account.id = UUID.randomUUID().toString();
        account.email = email;
        account.role = role;
        account.stationId = stationId;
        account.passwordHash = hashPassword(password);
        accounts.put(email, account);
        accountsById.put(account.id, account);
    }

    private String normalizeEmail(String input) {
        if (input == null || !input.contains("@") || input.length() > 254) throw badRequest("Enter a valid email address.");
        return input.trim().toLowerCase(Locale.ROOT);
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt);
        return HASH_ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    private boolean verifyPassword(String password, String encoded) {
        if (password == null || encoded == null) return false;
        try {
            String[] parts = encoded.split(":", 3);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            return Integer.parseInt(parts[0]) == HASH_ITERATIONS && MessageDigest.isEqual(expected, derive(password, salt));
        } catch (RuntimeException error) { return false; }
    }

    private byte[] derive(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_ITERATIONS, HASH_BITS);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception error) { throw new IllegalStateException("Password hashing is unavailable.", error); }
        finally { spec.clearPassword(); }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
