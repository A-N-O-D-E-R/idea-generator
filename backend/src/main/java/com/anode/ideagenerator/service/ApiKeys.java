package com.anode.ideagenerator.service;

import com.anode.ideagenerator.AppError;
import com.anode.ideagenerator.config.Security.TenantUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal API keys {@code idg_<random><crc32>} (same shape as ai-scientist's {@code ais_}): 32 random bytes
 * (base64url) plus a CRC32 of them, so typos and secret scanners can spot a key without a database lookup. Only the
 * SHA-256 is stored. A key signs requests in as its tenant, no session and no CSRF token.
 */
@Service
@DependsOnDatabaseInitialization // Flyway first
@RequiredArgsConstructor
public class ApiKeys implements OpaqueTokenIntrospector {
    public static final String PREFIX = "idg_";
    public static final int MAX_ACTIVE = 10;
    public static final Duration DEFAULT_TTL = Duration.ofDays(90);
    /** Authority carried by requests authenticated with an API key rather than a browser session. */
    public static final String AUTHORITY = "API_KEY";

    public record Created(UUID id, String name, String key, Instant expiresAt) {}

    public record Key(UUID id, String name, String hint, Instant createdAt, Instant expiresAt, Instant lastUsedAt) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient db;

    @Transactional
    public Created create(String tenant, String name, Duration ttl) {
        db.sql("SELECT id FROM tenants WHERE id = ? FOR UPDATE").param(tenant).query().singleValue();
        int active = db.sql("SELECT count(*) FROM api_keys WHERE tenant_id = ? AND revoked_at IS NULL AND expires_at > now()")
                .param(tenant).query(Integer.class).single();
        if (active >= MAX_ACTIVE) throw new AppError(409, "too_many_keys", "You already have " + MAX_ACTIVE + " active API keys; revoke one first.");
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String random = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String key = PREFIX + random + checksum(random);
        var id = UUID.randomUUID();
        var expiresAt = Instant.now().plus(ttl);
        db.sql("INSERT INTO api_keys (id, tenant_id, name, hash, hint, expires_at) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, tenant, name, sha256(key), key.substring(0, PREFIX.length() + 4) + "…", java.sql.Timestamp.from(expiresAt)).update();
        return new Created(id, name, key, expiresAt);
    }

    /** Active keys only. */
    public List<Key> list(String tenant) {
        return db.sql("""
                        SELECT id, name, hint, created_at, expires_at, last_used_at FROM api_keys
                        WHERE tenant_id = ? AND revoked_at IS NULL AND expires_at > now() ORDER BY created_at""")
                .param(tenant).query((rs, n) -> new Key(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant())).list();
    }

    public boolean revoke(String tenant, UUID id) {
        return db.sql("UPDATE api_keys SET revoked_at = now() WHERE id = ? AND tenant_id = ? AND revoked_at IS NULL")
                .params(id, tenant).update() == 1;
    }

    /** Spring Security entry point: an active key becomes its tenant's principal. */
    @Override
    public TenantUser introspect(String token) {
        return authenticate(token).orElseThrow(() -> new BadOpaqueTokenException("invalid or expired API key"));
    }

    Optional<TenantUser> authenticate(String token) {
        if (!wellFormed(token)) return Optional.empty();
        return db.sql("""
                        UPDATE api_keys SET last_used_at = now()
                        WHERE hash = ? AND revoked_at IS NULL AND expires_at > now() RETURNING id, tenant_id""")
                .param(sha256(token))
                .query((rs, n) -> new TenantUser(rs.getString(2), "api-key:" + rs.getString(1), new DefaultOAuth2User(
                        List.of(new SimpleGrantedAuthority(AUTHORITY)), Map.of("apiKey", rs.getString(1)), "apiKey")))
                .optional();
    }

    static boolean wellFormed(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() != PREFIX.length() + 43 + 8) return false;
        String random = token.substring(PREFIX.length(), PREFIX.length() + 43);
        return checksum(random).equals(token.substring(PREFIX.length() + 43));
    }

    private static String checksum(String random) {
        var crc = new CRC32();
        crc.update(random.getBytes(StandardCharsets.US_ASCII));
        return "%08x".formatted(crc.getValue());
    }

    private static byte[] sha256(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
