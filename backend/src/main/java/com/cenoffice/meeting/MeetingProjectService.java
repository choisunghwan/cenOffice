package com.cenoffice.meeting;

import com.cenoffice.common.api.ApiException;
import com.cenoffice.hub.HubDtos.SkillDetail;
import com.cenoffice.hub.SkillService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class MeetingProjectService {
    public record Project(String id, String name, String owner, Long skillId, Long versionId, List<String> members) {}
    public record Settings(String projectId, List<Project> projects) {}
    public record DraftSkill(String label, String instructions) {}
    private static final RowMapper<Project> ROW = (r, n) -> new Project(r.getString("id"), r.getString("name"),
        r.getString("owner_username"), r.getObject("skill_id", Long.class), r.getObject("version_id", Long.class), List.of());
    private final JdbcTemplate db;
    private final SkillService skills;

    public MeetingProjectService(JdbcTemplate db, SkillService skills) {
        this.db = db;
        this.skills = skills;
    }

    @Transactional(readOnly = true)
    public List<Project> list(String actor) {
        return db.query("""
            select p.* from meeting_projects p join meeting_project_members m on p.id=m.project_id
            where m.username=? order by p.name,p.id
            """, ROW, actor).stream().map(p -> withMembers(p, actor)).toList();
    }

    public Project create(String actor, String name, List<String> members, Long skillId, Long versionId) {
        String id = UUID.randomUUID().toString();
        db.update("insert into meeting_projects(id,name,owner_username) values(?,?,?)", id, name.strip(), actor);
        return update(id, actor, name, members, skillId, versionId);
    }

    public Project update(String id, String actor, String name, List<String> members, Long skillId, Long versionId) {
        Project project = project(id, true);
        if (!project.owner().equals(actor)) throw new ApiException(HttpStatus.FORBIDDEN, "프로젝트 소유자만 설정할 수 있습니다");
        if ((skillId == null) != (versionId == null)) throw new ApiException(HttpStatus.BAD_REQUEST, "스킬과 버전을 함께 선택하세요");
        if (skillId != null) approvedSkill(skillId, versionId, actor);
        Set<String> participants = new LinkedHashSet<>();
        participants.add(actor);
        if (members != null) for (String input : members) {
            String member = input.strip();
            if (!exists("select count(*) from users where username=?", member))
                throw new ApiException(HttpStatus.BAD_REQUEST, "참여자 아이디를 확인하세요");
            participants.add(member);
        }
        db.update("update meeting_projects set name=?, skill_id=?, version_id=? where id=?", name.strip(), skillId, versionId, id);
        db.update("delete from meeting_project_members where project_id=?", id);
        for (String member : participants) db.update("insert into meeting_project_members(project_id,username) values(?,?)", id, member);
        return withMembers(project(id, false), actor);
    }

    @Transactional(readOnly = true)
    public Settings settings(String meetingId, String actor) {
        requireHost(meetingId, actor, false);
        return new Settings(assignedProject(meetingId), list(actor));
    }

    public Settings assign(String meetingId, String actor, String projectId) {
        requireHost(meetingId, actor, true);
        if (exists("select count(*) from meeting_minutes where meeting_id=? and status='CONFIRMED'", meetingId))
            throw new ApiException(HttpStatus.CONFLICT, "확정된 회의록의 프로젝트는 변경할 수 없습니다");
        if (projectId != null) requireParticipant(projectId, actor);
        db.update("update collaboration_meetings set project_id=? where id=?", projectId, meetingId);
        return new Settings(projectId, list(actor));
    }

    String assignedProject(String meetingId) {
        return db.queryForObject("select project_id from collaboration_meetings where id=?", String.class, meetingId);
    }

    DraftSkill draftSkill(String projectId, String actor) {
        if (projectId == null) return null;
        requireParticipant(projectId, actor);
        Project p = project(projectId, false);
        if (p.skillId() == null) return null;
        var skill = approvedSkill(p.skillId(), p.versionId(), actor);
        return new DraftSkill(skill.summary().name() + "@" + skill.current().meta().version(), skill.current().skillMd());
    }

    private SkillDetail approvedSkill(long id, long version, String actor) {
        var skill = skills.detail(id, version, actor, false);
        var current = skill.current();
        if (!"APPROVED".equals(skill.summary().status()) || skill.summary().sample() || current == null
                || !"APPROVED".equals(current.meta().status()) || !"MD".equals(current.meta().packageKind())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "승인된 단일 SKILL.md 버전만 회의록에 연결할 수 있습니다");
        }
        return skill;
    }

    private Project project(String id, boolean lock) {
        return db.query("select * from meeting_projects where id=?" + (lock ? " for update" : ""), ROW, id)
            .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다"));
    }

    private Project withMembers(Project p, String actor) {
        var members = p.owner().equals(actor) ? db.queryForList(
            "select username from meeting_project_members where project_id=? order by username", String.class, p.id()) : List.<String>of();
        return new Project(p.id(), p.name(), p.owner(), p.skillId(), p.versionId(), members);
    }

    private void requireParticipant(String projectId, String actor) {
        if (!exists("select count(*) from meeting_project_members where project_id=? and username=?", projectId, actor))
            throw new ApiException(HttpStatus.NOT_FOUND, "참여 중인 프로젝트를 찾을 수 없습니다");
    }

    private void requireHost(String meetingId, String actor, boolean lock) {
        var host = db.queryForList("select host_username from collaboration_meetings where id=?" + (lock ? " for update" : ""), String.class, meetingId);
        if (host.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "회의를 찾을 수 없습니다");
        if (!actor.equals(host.getFirst())) throw new ApiException(HttpStatus.FORBIDDEN, "회의 진행자만 프로젝트를 지정할 수 있습니다");
    }

    private boolean exists(String sql, Object... args) {
        return db.queryForObject(sql, Integer.class, args) > 0;
    }
}
