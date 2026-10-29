package com.cenoffice.office.device;

import com.cenoffice.office.device.CommandDispatcher;
import com.cenoffice.office.device.DeviceService;
import com.cenoffice.office.device.DeviceTypes.CommandRequest;
import com.cenoffice.office.device.DeviceTypes.CommandView;
import com.cenoffice.office.device.DeviceTypes.DeviceView;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DeviceController {
    private final DeviceService service;
    private final CommandDispatcher dispatcher;

    public DeviceController(DeviceService service, CommandDispatcher dispatcher) {
        this.service = service;
        this.dispatcher = dispatcher;
    }

    @GetMapping("/devices")
    public List<DeviceView> list() { return service.list(); }

    @GetMapping("/devices/{id}")
    public DeviceView get(@PathVariable Long id) { return service.get(id); }

    /** 명령 접수(REQUESTED). 결과는 WebSocket(command.update / device.state) 또는 명령 조회로 확인한다. */
    @PostMapping("/devices/{id}/commands")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public CommandView send(@PathVariable Long id, @Valid @RequestBody CommandRequest req, Principal who) {
        CommandView v = service.submit(id, req, who.getName());
        dispatcher.dispatch(v.id());
        return v;
    }

    @GetMapping("/devices/{id}/commands")
    public List<CommandView> history(@PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
        return service.history(id, limit);
    }

    @GetMapping("/commands/{id}")
    public CommandView command(@PathVariable UUID id) { return service.command(id); }
}
