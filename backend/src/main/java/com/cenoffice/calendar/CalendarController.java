package com.cenoffice.calendar;

import com.cenoffice.api.ApiException;
import com.cenoffice.api.AuditService;
import com.cenoffice.repo.UserRepository;
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
    public record UserRow(String username, String displayName, String team, String role, boolean calendarManager) {}

    private final CalendarService calendar;
    private final UserRepository users;
    private final AuditService audit;

    public CalendarController(CalendarService calendar, UserRepository users, AuditService audit) { this.calendar = calendar; this.users = users; this.audit = audit; }

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
    public List<UserRow> userList() {
        return users.findAll().stream()
            .map(u -> new UserRow(u.getUsername(), u.getDisplayName(), u.getTeam(), u.getRole().name(), u.isCalendarManager()))
            .sorted((x, y) -> x.displayName().compareToIgnoreCase(y.displayName())).toList();
    }

    @PutMapping("/api/admin/users/{username}/calendar-manager") @PreAuthorize("hasRole('ADMIN')")
    public UserRow setManager(@PathVariable String username, @RequestBody ManagerRequest r, Authentication a) {
        var u = users.findByUsername(username).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"));
        u.setCalendarManager(r.enabled());
        users.save(u);
        audit.log(a.getName(), "CALENDAR_MANAGER", "user:" + username, r.enabled() ? "일정 관리자 지정" : "일정 관리자 해제");
        return new UserRow(u.getUsername(), u.getDisplayName(), u.getTeam(), u.getRole().name(), u.isCalendarManager());
    }
}
