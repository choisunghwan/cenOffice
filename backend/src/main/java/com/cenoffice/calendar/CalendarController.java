package com.cenoffice.calendar;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class CalendarController {
    public record ManagerRequest(boolean enabled) {}

    private final CalendarService calendar;
    private final CalendarManagerService managers;

    public CalendarController(CalendarService calendar, CalendarManagerService managers) { this.calendar = calendar; this.managers = managers; }

    private static boolean admin(Authentication a) { return a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN")); }

    /** scope=shared(전체) | mine(개인). 기간은 최대 62일 */
    @GetMapping("/api/calendar")
    public CalendarService.Feed feed(@RequestParam(defaultValue = "mine") String scope, @RequestParam Instant from, @RequestParam Instant to, Authentication a) {
        return calendar.feed(a.getName(), admin(a), scope, from, to);
    }

    @GetMapping("/api/calendar/people")
    public List<CalendarService.Person> people() { return calendar.directory(); }

    @PostMapping("/api/calendar/events") @ResponseStatus(HttpStatus.CREATED)
    public CalendarService.Entry create(@Valid @RequestBody CalendarService.Request r, Authentication a) { return calendar.create(a.getName(), admin(a), r); }

    @PutMapping("/api/calendar/events/{id}")
    public CalendarService.Entry update(@PathVariable long id, @Valid @RequestBody CalendarService.Request r, Authentication a) { return calendar.update(a.getName(), admin(a), id, r); }

    @DeleteMapping("/api/calendar/events/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, Authentication a) { calendar.delete(a.getName(), admin(a), id); }

    // ── 관리자: 일정 관리자(팀장 등) 지정 ──

    @GetMapping("/api/admin/users") @PreAuthorize("hasRole('ADMIN')")
    public List<CalendarManagerService.UserRow> userList() { return managers.userList(); }

    @PutMapping("/api/admin/users/{username}/calendar-manager") @PreAuthorize("hasRole('ADMIN')")
    public CalendarManagerService.UserRow setManager(@PathVariable String username, @RequestBody ManagerRequest r, Authentication a) {
        return managers.setManager(a.getName(), username, r.enabled());
    }
}
