package com.cenoffice.common.api;

import com.cenoffice.common.domain.Enums.Role;
import com.cenoffice.common.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자의 사용자 삭제. 계정과 그 사람만 쓰는 개인 데이터(액세스 토큰·AI 대화 기록·알림·대화방/일정 참여)를 지운다.
 * 그 사람이 만든 스킬·회의록·일정·예약처럼 다른 사람이 함께 보는 기록과 감사 로그는 남긴다.
 */
@Service
public class UserAdminService {
    /** username 열로 사람을 가리키는 개인 데이터 (테이블, 열) */
    private static final String[][] PERSONAL = {
        {"hub_tokens", "owner"}, {"assistant_sessions", "owner"}, {"notifications", "username"},
        {"chat_room_members", "username"}, {"calendar_attendees", "username"}};

    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public UserAdminService(UserRepository users, JdbcTemplate jdbc, AuditService audit) { this.users = users; this.jdbc = jdbc; this.audit = audit; }

    @Transactional
    public void delete(String actor, String username) {
        if (username.equals(actor)) throw new ApiException(HttpStatus.BAD_REQUEST, "내 계정은 지울 수 없습니다");
        var u = users.findByUsername(username).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"));
        if (u.getRole() == Role.ADMIN && users.countByRole(Role.ADMIN) <= 1) throw new ApiException(HttpStatus.CONFLICT, "마지막 관리자는 지울 수 없습니다");
        for (String[] t : PERSONAL) jdbc.update("DELETE FROM " + t[0] + " WHERE " + t[1] + " = ?", username);
        users.delete(u);
        audit.log(actor, "USER_DELETED", "user:" + username, u.getDisplayName());
    }
}
