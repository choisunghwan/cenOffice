package com.cenoffice.chat;

import com.cenoffice.chat.ChatService.MessageView;
import com.cenoffice.chat.ChatService.PersonView;
import com.cenoffice.chat.ChatService.RoomView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private static final String STAFF = "hasAnyRole('ADMIN','USER')";

    public record DirectRequest(@NotBlank @Size(max = 64) String username) {}
    public record AiRequest(@NotBlank @Size(max = 32) String agentCode) {}
    // 길이 검증은 서비스에서 코드포인트 기준으로 수행(이 값은 상한 가드)
    public record SendRequest(@NotBlank @Size(max = 4000) String content) {}

    private final ChatService chat;

    public ChatController(ChatService chat) { this.chat = chat; }

    @GetMapping("/people") @PreAuthorize(STAFF)
    public List<PersonView> people() { return chat.people(); }

    @GetMapping("/rooms") @PreAuthorize(STAFF)
    public List<RoomView> rooms(Principal p) { return chat.myRooms(p.getName()); }

    @PostMapping("/rooms/direct") @PreAuthorize(STAFF)
    public RoomView direct(@Valid @RequestBody DirectRequest r, Principal p) { return chat.directRoom(p.getName(), r.username()); }

    @PostMapping("/rooms/ai") @PreAuthorize(STAFF)
    public RoomView ai(@Valid @RequestBody AiRequest r, Principal p) { return chat.aiRoom(p.getName(), r.agentCode()); }

    @GetMapping("/rooms/{id}/messages") @PreAuthorize(STAFF)
    public List<MessageView> messages(@PathVariable Long id, @RequestParam(required = false) Long before,
                                      @RequestParam(defaultValue = "50") int limit, Principal p) {
        return chat.history(p.getName(), id, before, limit);
    }

    @PostMapping("/rooms/{id}/messages") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(STAFF)
    public MessageView send(@PathVariable Long id, @Valid @RequestBody SendRequest r, Principal p) {
        return chat.send(p.getName(), id, r.content());
    }
}
