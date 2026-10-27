package com.cenoffice.notify;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 내 이메일 알림 설정. 본인 설정만 조회·수정할 수 있다. */
@RestController
@RequestMapping("/api/me/email")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class MailController {
    private final MailService mail;
    public MailController(MailService mail) { this.mail = mail; }

    public record Update(@Size(max = 254) String email, boolean enabled) {}

    @GetMapping public MailService.Settings get(Authentication a) { return mail.settings(a.getName()); }
    @PutMapping public MailService.Settings put(@Valid @RequestBody Update r, Authentication a) { return mail.update(a.getName(), r.email(), r.enabled()); }
    @PostMapping("/test") @ResponseStatus(HttpStatus.NO_CONTENT) public void test(Authentication a) { mail.sendTest(a.getName()); }
}
