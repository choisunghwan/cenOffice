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

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/login")
    public AuthService.LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return auth.login(req.username(), req.password(), http.getRemoteAddr());
    }

    @PostMapping("/signup")
    public AuthService.LoginResponse signup(@Valid @RequestBody SignupRequest req, HttpServletRequest http) {
        return auth.signup(req.username(), req.displayName(), req.email(), req.password(), http.getRemoteAddr());
    }

    @GetMapping("/me")
    public AuthService.UserView me(Principal p) { return auth.me(p.getName()); }
}
