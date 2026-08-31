package io.github.ande1922.moduvera.reference.app.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class IdentityRepository {

    private final JdbcTemplate jdbc;

    public IdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> userByUsername(String username) {
        return jdbc.query(
                        """
                        SELECT user_id, username, password_hash
                          FROM identity_user
                         WHERE username = ? AND enabled
                        """,
                        (result, row) -> new UserAccount(
                                result.getString("user_id"),
                                result.getString("username"),
                                result.getString("password_hash")),
                        username)
                .stream()
                .findFirst();
    }

    public boolean isMember(String userId, String tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity_tenant_membership WHERE user_id = ? AND tenant_id = ?",
                Integer.class,
                userId,
                tenantId);
        return count != null && count == 1;
    }

    public List<String> permissions(String userId, String tenantId) {
        return jdbc.queryForList(
                """
                SELECT permission
                  FROM identity_permission_assignment
                 WHERE user_id = ? AND tenant_id = ?
                 ORDER BY permission
                """,
                String.class,
                userId,
                tenantId);
    }

    public void saveSession(String tokenHash, String userId, String tenantId, Instant expiresAt) {
        jdbc.update(
                """
                INSERT INTO identity_browser_session
                    (token_hash, user_id, tenant_id, issued_at, expires_at, revoked)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, false)
                """,
                tokenHash,
                userId,
                tenantId,
                Timestamp.from(expiresAt));
    }

    public Optional<SessionAccount> activeSession(String tokenHash, Instant now) {
        return jdbc.query(
                        """
                        SELECT session.user_id, session.tenant_id, session.expires_at, users.username
                          FROM identity_browser_session session
                          JOIN identity_user users ON users.user_id = session.user_id
                         WHERE session.token_hash = ?
                           AND NOT session.revoked
                           AND session.expires_at > ?
                           AND users.enabled
                        """,
                        (result, row) -> new SessionAccount(
                                result.getString("user_id"),
                                result.getString("username"),
                                result.getString("tenant_id"),
                                result.getTimestamp("expires_at").toInstant()),
                        tokenHash,
                        Timestamp.from(now))
                .stream()
                .findFirst();
    }

    public Optional<ServiceAccount> service(String serviceId) {
        return jdbc.query(
                        """
                        SELECT service_id, secret_hash
                          FROM identity_service
                         WHERE service_id = ? AND enabled
                        """,
                        (result, row) -> new ServiceAccount(
                                result.getString("service_id"), result.getString("secret_hash")),
                        serviceId)
                .stream()
                .findFirst();
    }

    public List<String> servicePermissions(String serviceId, String audience) {
        return jdbc.queryForList(
                """
                SELECT permission
                  FROM identity_service_permission
                 WHERE service_id = ? AND audience = ?
                 ORDER BY permission
                """,
                String.class,
                serviceId,
                audience);
    }

    public record UserAccount(String userId, String username, String passwordHash) {}

    public record SessionAccount(String userId, String username, String tenantId, Instant expiresAt) {}

    public record ServiceAccount(String serviceId, String secretHash) {}
}
