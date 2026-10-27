package com.cenoffice.meeting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/meetings")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class CollaborationController {
    private final CollaborationService meetings;
    private final MeetingMedia media;
    public CollaborationController(CollaborationService meetings,MeetingMedia media) {this.meetings=meetings;this.media=media;}
    public record Create(@NotBlank @Size(max=120) String title) {}
    public record Join(@NotBlank @Size(max=32) String code) {}
    public record Chat(@NotBlank @Size(max=1000) String content) {}
    @GetMapping public List<CollaborationService.Meeting> list(Authentication a) {return meetings.list(a.getName());}
    @GetMapping("/page") public CollaborationService.Page page(@RequestParam(required=false) String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,Authentication a) {return meetings.search(a.getName(),q,page,size);}
    @GetMapping("/media-status") public Map<String,Object> status() {return Map.of("configured",media.configured(),"available",media.available());}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public CollaborationService.Meeting create(@Valid @RequestBody Create r,Authentication a) {return meetings.create(r.title(),a.getName());}
    @PostMapping("/join") public CollaborationService.Meeting join(@Valid @RequestBody Join r,Authentication a) {return meetings.join(r.code(),a.getName());}
    @GetMapping("/{id}") public CollaborationService.Meeting detail(@PathVariable String id,Authentication a) {return meetings.detail(id,a.getName());}
    @GetMapping("/{id}/members") public List<CollaborationService.Member> members(@PathVariable String id,Authentication a) {return meetings.members(id,a.getName());}
    @PostMapping("/{id}/join-token") public MeetingMedia.Grant grant(@PathVariable String id,Authentication a) {return meetings.grant(id,a.getName());}
    @PostMapping("/{id}/end") @ResponseStatus(HttpStatus.NO_CONTENT) public void end(@PathVariable String id,Authentication a) {meetings.end(id,a.getName());}
    @GetMapping("/{id}/messages") public List<CollaborationService.Message> messages(@PathVariable String id,Authentication a) {return meetings.messages(id,a.getName());}
    @PostMapping("/{id}/messages") @ResponseStatus(HttpStatus.NO_CONTENT) public void send(@PathVariable String id,@Valid @RequestBody Chat r,Authentication a) {meetings.send(id,r.content(),a.getName());}
}
