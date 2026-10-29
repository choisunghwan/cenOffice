package com.cenoffice.hub;

import com.cenoffice.hub.domain.HubToken;
import com.cenoffice.hub.domain.HubTokenRepository;

import com.cenoffice.api.ApiException;
import com.cenoffice.api.AuditService;
import com.cenoffice.domain.Enums.Role;
import com.cenoffice.repo.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PersonalTokenService {
    public static final String PREFIX = "cen_pat_";
    public record Principal(String username, boolean admin) {}
    public record TokenView(Long id, String name, String prefix, Instant createdAt, Instant expiresAt, Instant lastUsedAt, boolean revoked, boolean expired) {}
    public record Created(TokenView token, String secret) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private final HubTokenRepository tokens;
    private final UserRepository users;
    private final AuditService audit;

    public PersonalTokenService(HubTokenRepository tokens, UserRepository users, AuditService audit) { this.tokens = tokens; this.users = users; this.audit = audit; }

    static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public Created create(String owner, String name, Integer days) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 64) throw new ApiException(HttpStatus.BAD_REQUEST, "토큰 이름은 1~64자여야 합니다");
        if (tokens.countByOwnerAndRevokedFalse(owner) >= 10) throw new ApiException(HttpStatus.BAD_REQUEST, "토큰은 최대 10개까지 만들 수 있습니다. 사용하지 않는 토큰을 폐기하세요");
        int d = days == null ? 90 : Math.max(1, Math.min(365, days));
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String secret = PREFIX + HexFormat.of().formatHex(b);
        var t = new HubToken();
        t.owner = owner; t.name = n; t.tokenHash = hash(secret); t.tokenPrefix = secret.substring(0, PREFIX.length() + 4);
        t.createdAt = Instant.now(); t.expiresAt = t.createdAt.plus(Duration.ofDays(d));
        t = tokens.save(t);
        audit.log(owner, "HUB_TOKEN_CREATED", "hub-token:" + t.id, n);
        return new Created(view(t), secret);
    }

    public List<TokenView> list(String owner) { return tokens.findByOwnerOrderByCreatedAtDesc(owner).stream().map(this::view).toList(); }

    public void revoke(String owner, Long id) {
        var t = tokens.findById(id).filter(x -> x.owner.equals(owner)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "토큰을 찾을 수 없습니다"));
        t.revoked = true;
        tokens.save(t);
        audit.log(owner, "HUB_TOKEN_REVOKED", "hub-token:" + id, t.name);
    }

    /** 유효한 토큰이면 소유자 정보를 돌려준다(폐기·만료·알 수 없는 토큰·삭제된 계정은 empty). */
    public Optional<Principal> authenticate(String raw) {
        if (raw == null || !raw.startsWith(PREFIX) || raw.length() > 128) return Optional.empty();
        var t = tokens.findByTokenHash(hash(raw)).orElse(null);
        if (t == null || t.revoked || t.expiresAt.isBefore(Instant.now())) return Optional.empty();
        var u = users.findByUsername(t.owner).orElse(null);
        if (u == null) return Optional.empty();
        if (t.lastUsedAt == null || t.lastUsedAt.isBefore(Instant.now().minusSeconds(60))) { t.lastUsedAt = Instant.now(); tokens.save(t); }
        return Optional.of(new Principal(u.getUsername(), u.getRole() == Role.ADMIN));
    }

    private TokenView view(HubToken t) {
        return new TokenView(t.id, t.name, t.tokenPrefix + "…", t.createdAt, t.expiresAt, t.lastUsedAt, t.revoked, t.expiresAt.isBefore(Instant.now()));
    }
}
