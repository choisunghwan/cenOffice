package com.cenoffice.home;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class AssistantController {
    public record Status(boolean ai) {}

    private final AssistantService assistant;
    public AssistantController(AssistantService assistant) { this.assistant = assistant; }

    /** AI 가 연결되어 있는지(연결되면 입력 문장과 동료 이름이 해당 AI 서비스로 전송된다는 안내에 쓴다) */
    @GetMapping("/status") public Status status() { return new Status(assistant.aiConnected()); }

    @PostMapping("/interpret")
    public AssistantService.Reply interpret(@Valid @RequestBody AssistantService.Ask ask, Authentication a) {
        boolean admin = a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN"));
        return assistant.interpret(a.getName(), admin, ask);
    }
}
