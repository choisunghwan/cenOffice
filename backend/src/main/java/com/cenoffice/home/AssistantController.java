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

    public record SuggestRequest(@jakarta.validation.constraints.Size(max = 12) @Valid java.util.List<AssistantService.HistoryTurn> history) {}
    public record Suggestions(java.util.List<String> suggestions) {}

    private final AssistantService assistant;
    private final NextPromptService next;
    public AssistantController(AssistantService assistant, NextPromptService next) { this.assistant = assistant; this.next = next; }

    /** 입력창 자동완성: 대화를 보고 다음에 입력할 만한 말을 AI 가 제안한다(실행은 하지 않음) */
    @PostMapping("/suggest")
    public Suggestions suggest(@Valid @RequestBody SuggestRequest r, Authentication a) { return new Suggestions(next.suggest(a.getName(), r.history())); }

    /** AI 가 연결되어 있는지(연결되면 입력 문장과 동료 이름이 해당 AI 서비스로 전송된다는 안내에 쓴다) */
    @GetMapping("/status") public Status status() { return new Status(assistant.aiConnected()); }

    @PostMapping("/interpret")
    public AssistantService.Reply interpret(@Valid @RequestBody AssistantService.Ask ask, Authentication a) {
        boolean admin = a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN"));
        return assistant.interpret(a.getName(), admin, ask);
    }
}
