package com.cenoffice.meeting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/meetings/{id}/assistant")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class MeetingAssistantController {
    private final MeetingAssistantService service;
    public MeetingAssistantController(MeetingAssistantService service){this.service=service;}
    public record Ask(@NotBlank @Size(max=1200) String question){}
    @GetMapping public MeetingAssistantService.State state(@PathVariable String id,Authentication a){return service.status(id,a.getName());}
    @PostMapping("/enable") public MeetingAssistantService.State enable(@PathVariable String id,Authentication a){return service.enable(id,a.getName());}
    @PostMapping("/disable") public MeetingAssistantService.State disable(@PathVariable String id,Authentication a){return service.disable(id,a.getName());}
    @PostMapping("/stop") public MeetingAssistantService.State stop(@PathVariable String id,Authentication a){return service.stop(id,a.getName());}
    @PostMapping("/ask") @ResponseStatus(HttpStatus.ACCEPTED)
    public MeetingAssistantService.State ask(@PathVariable String id,@Valid @RequestBody Ask r,Authentication a){return service.ask(id,a.getName(),r.question());}
    @GetMapping("/turns/{turn}/audio") public ResponseEntity<byte[]> audio(@PathVariable String id,@PathVariable String turn,Authentication a){
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).cacheControl(CacheControl.noStore()).body(service.audio(id,a.getName(),turn));
    }
    @PostMapping("/turns/{turn}/grant") public MeetingMedia.Grant grant(@PathVariable String id,@PathVariable String turn,Authentication a){return service.grant(id,a.getName(),turn);}
    @PostMapping("/turns/{turn}/playing") public MeetingAssistantService.State playing(@PathVariable String id,@PathVariable String turn,Authentication a){return service.playing(id,a.getName(),turn);}
    @PostMapping("/turns/{turn}/complete") public MeetingAssistantService.State complete(@PathVariable String id,@PathVariable String turn,Authentication a){return service.complete(id,a.getName(),turn);}
}
