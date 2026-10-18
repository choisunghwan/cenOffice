package com.cenoffice.api;

import com.cenoffice.config.AppProperties;
import com.cenoffice.domain.UserAccount;
import com.cenoffice.repo.UserRepository;
import com.cenoffice.security.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record UserView(String username, String displayName, String role, String team) {}
    public record LoginResponse(String token, Instant expiresAt, UserView user) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;
    private final AppProperties props;
    private final AuditService audit;
    private final String dummyHash;
    private final LoginRateLimiter limiter;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtEncoder jwt, AppProperties props, AuditService audit, LoginRateLimiter limiter) {
        this.limiter = limiter;
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
        this.audit = audit;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        long locked = limiter.lockedSeconds(req.username(), ip);
        if (locked > 0) {
            audit.log(req.username(), "LOGIN_LOCKED", null, "ip=" + ip);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 너무 많습니다. " + locked + "초 후 다시 시도하세요");
        }
        UserAccount u = users.findByUsername(req.username()).orElse(null);
        // 사용자 존재 여부와 무관하게 동일한 응답/시간 특성을 유지
        boolean ok = encoder.matches(req.password(), u == null ? dummyHash : u.getPasswordHash()) && u != null;
        if (!ok) {
            limiter.recordFailure(req.username(), ip);
            audit.log(req.username(), "LOGIN_FAILED", null, "ip=" + ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다");
        }
        limiter.recordSuccess(req.username(), ip);
        Instant now = Instant.now();
        Instant exp = now.plus(props.jwt().ttlMinutes(), ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(u.getUsername()).issuedAt(now).expiresAt(exp)
                .claim("role", u.getRole().name()).claim("name", u.getDisplayName()).build();
        String token = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        audit.log(u.getUsername(), "LOGIN", null, null);
        return new LoginResponse(token, exp, new UserView(u.getUsername(), u.getDisplayName(), u.getRole().name(), u.getTeam()));
    }

    @GetMapping("/me")
    public UserView me(Principal p) {
        UserAccount u = users.findByUsername(p.getName()).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다"));
        return new UserView(u.getUsername(), u.getDisplayName(), u.getRole().name(), u.getTeam());
    }
}
