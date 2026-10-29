package com.cenoffice.calendar;

import com.cenoffice.api.ApiException;
import com.cenoffice.api.AuditService;
import com.cenoffice.domain.UserAccount;
import com.cenoffice.repo.UserRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 관리자가 일정 관리자(팀장 등)를 지정하는 기능. */
@Service
public class CalendarManagerService {
    public record UserRow(String username, String displayName, String team, String role, boolean calendarManager) {}

    private final UserRepository users;
    private final AuditService audit;

    public CalendarManagerService(UserRepository users, AuditService audit) { this.users = users; this.audit = audit; }

    public List<UserRow> userList() {
        return users.findAll().stream().map(CalendarManagerService::row)
            .sorted((x, y) -> x.displayName().compareToIgnoreCase(y.displayName())).toList();
    }

    public UserRow setManager(String actor, String username, boolean enabled) {
        var u = users.findByUsername(username).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"));
        u.setCalendarManager(enabled);
        users.save(u);
        audit.log(actor, "CALENDAR_MANAGER", "user:" + username, enabled ? "일정 관리자 지정" : "일정 관리자 해제");
        return row(u);
    }

    private static UserRow row(UserAccount u) {
        return new UserRow(u.getUsername(), u.getDisplayName(), u.getTeam(), u.getRole().name(), u.isCalendarManager());
    }
}
