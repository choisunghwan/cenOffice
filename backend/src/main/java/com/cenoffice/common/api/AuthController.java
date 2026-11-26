package com.cenoffice.common.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record SignupRequest(@NotBlank String username, @NotBlank String displayName, String email, @NotBlank String password) {}

    private final AuthService auth;
    private final GuestService guests;

    public AuthController(AuthService auth, GuestService guests) { this.auth = auth; this.guests = guests; }

    @PostMapping("/login")
    public AuthService.LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return auth.login(req.username(), req.password(), http.getRemoteAddr());
    }

    @PostMapping("/signup")
    public AuthService.LoginResponse signup(@Valid @RequestBody SignupRequest req, HttpServletRequest http) {
        return auth.signup(req.username(), req.displayName(), req.email(), req.password(), http.getRemoteAddr());
    }

    public record GuestRequest(String username, String key) {}

    /** 바로 체험하기: 가입 없이 방문자 계정으로 들어간다. 받은 key 를 기기에 저장해 두면 다음에 같은 계정으로 들어온다 */
    @PostMapping("/guest")
    public GuestService.GuestLogin guest(@RequestBody(required = false) GuestRequest req, HttpServletRequest http) {
        return guests.enter(req == null ? null : req.username(), req == null ? null : req.key(), http.getRemoteAddr());
    }

    @GetMapping("/me")
    public AuthService.UserView me(Principal p) { return auth.me(p.getName()); }
}
