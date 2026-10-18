package com.cenoffice.hub;

import com.cenoffice.hub.PersonalTokenService.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 개인 액세스 토큰 관리 (로그인한 본인 것만). 토큰 원문은 생성 응답에서 한 번만 보여 준다. */
@RestController
@RequestMapping("/api/hub/tokens")
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class HubTokenController {
    public record CreateRequest(String name, Integer days) {}
    private final PersonalTokenService service;
    public HubTokenController(PersonalTokenService service) { this.service = service; }

    @GetMapping public List<TokenView> list(Authentication a) { return service.list(a.getName()); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Created create(@RequestBody CreateRequest r, Authentication a) { return service.create(a.getName(), r.name(), r.days()); }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long id, Authentication a) { service.revoke(a.getName(), id); }
}
