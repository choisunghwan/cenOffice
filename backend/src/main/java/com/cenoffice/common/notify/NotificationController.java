package com.cenoffice.common.notify;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class NotificationController {
    private final NotificationService notifications;
    public NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping public NotificationService.Page list(Authentication a) { return notifications.list(a.getName()); }

    @PostMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable long id, Authentication a) { notifications.markRead(a.getName(), id); }

    @PostMapping("/read-all") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(Authentication a) { notifications.markAllRead(a.getName()); }
}
