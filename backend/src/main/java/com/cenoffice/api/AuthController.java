package com.cenoffice.api;

import com.cenoffice.config.AppProperties;
import com.cenoffice.domain.Enums;
import com.cenoffice.domain.UserAccount;
import com.cenoffice.notify.MailService;
import org.springframework.dao.DataIntegrityViolationException;
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
    public record SignupRequest(@NotBlank String username, @NotBlank String displayName, String email, @NotBlank String password) {}
    private static final java.util.regex.Pattern USERNAME = java.util.regex.Pattern.compile("^[A-Za-z0-9._\\-]{3,32}$");
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
        audit.log(u.getUsername(), "LOGIN", null, null);
        return issue(u);
    }

    /**
     * 자체 가입. 항상 일반 사용자(USER)로만 만들어지며 관리자 권한은 가입으로 얻을 수 없다.
     * 아이디는 사번을 쓰는 것을 전제로 하고, 나중에 사내 계정(SSO/LDAP) 연동으로 바꿀 수 있다.
     */
    @PostMapping("/signup")
    public LoginResponse signup(@Valid @RequestBody SignupRequest req, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        String username = req.username().strip(), name = req.displayName().strip(), email = req.email() == null ? "" : req.email().strip();
        // 가입 시도는 성공/실패와 무관하게 센다 (IP당 10분에 5회). 사번 대량 선점·무차별 가입 방지
        if (limiter.lockedSeconds("signup", ip) > 0) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "가입 시도가 너무 많습니다. 잠시 후 다시 시도하세요");
        limiter.recordFailure("signup", ip);
        if (!USERNAME.matcher(username).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, "사번은 영문·숫자·._- 로 3~32자여야 합니다");
        if (name.isEmpty() || name.length() > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "이름을 입력하세요");
        if (req.password().length() < 8 || req.password().length() > 72) throw new ApiException(HttpStatus.BAD_REQUEST, "비밀번호는 8~72자여야 합니다");
        if (!email.isEmpty() && !MailService.validEmail(email)) throw new ApiException(HttpStatus.BAD_REQUEST, "이메일 주소 형식이 올바르지 않습니다");
        if (users.findByUsername(username).isPresent()) throw new ApiException(HttpStatus.CONFLICT, "이미 가입된 사번입니다");
        UserAccount u = new UserAccount(username, encoder.encode(req.password()), name, Enums.Role.USER);
        if (!email.isEmpty()) u.setEmail(email);
        try {
            u = users.saveAndFlush(u);
        } catch (DataIntegrityViolationException e) { // 동시 가입으로 같은 사번이 먼저 저장된 경우
            throw new ApiException(HttpStatus.CONFLICT, "이미 가입된 사번입니다");
        }
        audit.log(username, "SIGNUP", null, email.isEmpty() ? "이메일 없음" : "이메일 등록");
        return issue(u);
    }

    private LoginResponse issue(UserAccount u) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.jwt().ttlMinutes(), ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(u.getUsername()).issuedAt(now).expiresAt(exp)
                .claim("role", u.getRole().name()).claim("name", u.getDisplayName()).build();
        String token = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, exp, new UserView(u.getUsername(), u.getDisplayName(), u.getRole().name(), u.getTeam()));
    }

    @GetMapping("/me")
    public UserView me(Principal p) {
        UserAccount u = users.findByUsername(p.getName()).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다"));
        return new UserView(u.getUsername(), u.getDisplayName(), u.getRole().name(), u.getTeam());
    }
}
