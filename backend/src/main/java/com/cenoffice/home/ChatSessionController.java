package com.cenoffice.home;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 홈 입력창 채팅방: 만들기·목록·열기·저장·삭제 */
@RestController
@RequestMapping("/api/assistant/sessions")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class ChatSessionController {
    public record CreateRequest(String title) {}
    public record SaveRequest(String title, JsonNode turns) {}

    private final ChatSessionService sessions;

    public ChatSessionController(ChatSessionService sessions) { this.sessions = sessions; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ChatSessionService.Summary create(@RequestBody(required = false) CreateRequest r, Authentication a) { return sessions.create(a.getName(), r == null ? null : r.title()); }

    @GetMapping
    public List<ChatSessionService.Summary> list(Authentication a) { return sessions.list(a.getName()); }

    @GetMapping("/{id}")
    public ChatSessionService.Detail get(@PathVariable String id, Authentication a) { return sessions.get(a.getName(), id); }

    @PutMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@PathVariable String id, @RequestBody SaveRequest r, Authentication a) { sessions.save(a.getName(), id, r.title(), r.turns()); }

    /** 내 대화 기록 전체 삭제. 남의 것은 지워지지 않는다. */
    @DeleteMapping
    public Map<String, Integer> deleteAll(Authentication a) { return Map.of("deleted", sessions.deleteAll(a.getName())); }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, Authentication a) { sessions.delete(a.getName(), id); }
}
