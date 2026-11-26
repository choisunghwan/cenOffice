package com.cenoffice.common.api;

import com.cenoffice.common.domain.Enums.Role;
import com.cenoffice.common.domain.UserAccount;
import com.cenoffice.common.repo.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * '바로 체험하기': 가입 없이 들어오는 방문자 계정. 사람(기기)마다 따로 만들어 3D 오피스·대화가 섞이지 않게 한다.
 * 기기는 받은 열쇠(key)를 저장해 두었다가 다시 들어올 때 같은 계정을 이어 쓴다. 방문자는 스킬 등록·관리 기능을
 * 쓸 수 없고(GuestGuard), 며칠 뒤 자동으로 지운다. 발표장처럼 여러 명이 같은 IP 로 들어오므로 IP 제한은 넉넉히 둔다.
 */
@Service
public class GuestService {
    public record GuestLogin(AuthService.LoginResponse login, String key) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final UserAdminService admin;
    private final AuditService audit;
    private final int perIpPerHour, maxGuests;
    private final Duration keep;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(-1);

    public GuestService(UserRepository users, PasswordEncoder encoder, AuthService auth, UserAdminService admin, AuditService audit,
                        @Value("${app.guest.per-ip-per-hour:300}") int perIpPerHour,
                        @Value("${app.guest.max:1000}") int maxGuests,
                        @Value("${app.guest.keep-days:3}") int keepDays) {
        this.users = users; this.encoder = encoder; this.auth = auth; this.admin = admin; this.audit = audit;
        this.perIpPerHour = perIpPerHour; this.maxGuests = maxGuests; this.keep = Duration.ofDays(keepDays);
    }

    /** 저장해 둔 방문자 계정이 있으면 이어 쓰고, 없거나 지워졌으면 새로 만든다 */
    @Transactional
    public GuestLogin enter(String username, String key, String ip) {
        if (username != null && key != null) {
            var u = users.findByUsername(username).orElse(null);
            if (u != null && u.isGuest() && encoder.matches(key, u.getPasswordHash())) return new GuestLogin(auth.issue(u), key);
        }
        allow(ip);
        if (users.countByGuestTrue() >= maxGuests) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "체험 인원이 가득 찼습니다. 회원가입 후 이용해 주세요.");
        String name;
        do { name = "guest-" + HexFormat.of().formatHex(random.generateSeed(4)); } while (users.existsByUsername(name));
        byte[] k = new byte[24]; random.nextBytes(k);
        String newKey = Base64.getUrlEncoder().withoutPadding().encodeToString(k);
        if (seq.get() < 0) seq.compareAndSet(-1, (int) users.countByGuestTrue());
        var u = new UserAccount(name, encoder.encode(newKey), "방문자 " + seq.incrementAndGet(), Role.USER);
        u.markGuest();
        users.save(u);
        audit.log(name, "GUEST_CREATED", "user:" + name, "ip=" + ip);
        return new GuestLogin(auth.issue(u), newKey);
    }

    private void allow(String ip) {
        Deque<Instant> q = recent.computeIfAbsent(ip == null ? "-" : ip, x -> new ArrayDeque<>());
        synchronized (q) {
            Instant cutoff = Instant.now().minus(Duration.ofHours(1));
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
            if (q.size() >= perIpPerHour) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "체험 계정을 너무 많이 만들었습니다. 잠시 후 다시 시도하세요.");
            q.addLast(Instant.now());
        }
    }

    /** 오래된 방문자 계정과 개인 데이터를 지운다(매시간) */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void cleanup() { removeOlderThan(Instant.now().minus(keep)); }

    public int removeOlderThan(Instant before) {
        int n = 0;
        for (var u : users.findByGuestTrueAndCreatedAtBefore(before)) { admin.delete("system", u.getUsername()); n++; }
        return n;
    }

    /** 관리자: 방문자 계정을 모두 지운다 */
    public int removeAll(String actor) {
        int n = 0;
        for (var u : users.findByGuestTrue()) { admin.delete(actor, u.getUsername()); n++; }
        return n;
    }
}
