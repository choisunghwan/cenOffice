package com.cenoffice.meeting;

import com.cenoffice.api.*;
import com.cenoffice.repo.UserRepository;
import com.cenoffice.ws.WsEvents.UserEvent;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CollaborationService {
    private final JdbcTemplate db;
    private final UserRepository users;
    private final AuditService audit;
    private final MeetingMedia media;
    private final ApplicationEventPublisher events;
    public CollaborationService(JdbcTemplate db, UserRepository users, AuditService audit, MeetingMedia media, ApplicationEventPublisher events) {
        this.db=db;this.users=users;this.audit=audit;this.media=media;this.events=events;
    }
    public record Meeting(String id,String title,String host,String code,String state,Instant createdAt,int memberCount) {}
    public record Member(String username,String displayName,String team) {}
    public record Message(long id,String username,String displayName,String content,Instant sentAt) {}
    private Meeting row(ResultSet r,int i) throws SQLException {
        String id=r.getString("id");
        return new Meeting(id,r.getString("title"),r.getString("host_username"),r.getString("join_code"),r.getString("state"),r.getTimestamp("created_at").toInstant(),
            db.queryForObject("select count(*) from collaboration_members where meeting_id=?",Integer.class,id));
    }
    public List<Meeting> list(String actor) {
        return db.query("select m.* from collaboration_meetings m join collaboration_members p on p.meeting_id=m.id where p.username=? order by m.created_at desc limit 50",this::row,actor);
    }
    public Meeting detail(String id,String actor) {
        member(id,actor);
        return db.query("select * from collaboration_meetings where id=?",this::row,id).stream().findFirst().orElseThrow(this::notFound);
    }
    public Meeting create(String title,String actor) {
        users.findByUsername(actor).orElseThrow(this::notFound);
        if (title==null || title.isBlank() || title.strip().length()>120) throw bad("회의 제목은 1~120자로 입력하세요");
        if(db.queryForObject("select count(*) from collaboration_meetings where host_username=? and state='OPEN'",Integer.class,actor)>=10) throw bad("진행 중인 회의를 먼저 종료해 주세요 (최대 10개)");
        String id=UUID.randomUUID().toString(),code=UUID.randomUUID().toString().replace("-","");
        db.update("insert into collaboration_meetings(id,title,host_username,join_code,state,created_at) values(?,?,?,?,'OPEN',?)",id,title.strip(),actor,code,Timestamp.from(Instant.now()));
        db.update("insert into collaboration_members values(?,?,?)",id,actor,Timestamp.from(Instant.now()));
        audit.log(actor,"MEETING_CREATED","meeting:"+id,"회의 생성");
        return detail(id,actor);
    }
    public Meeting join(String code,String actor) {
        users.findByUsername(actor).orElseThrow(this::notFound);
        if(code==null || !code.matches("[a-f0-9]{32}")) throw notFound();
        // Serialize admission and end/token issuance against this meeting row.
        var ids=db.queryForList("select id from collaboration_meetings where join_code=? and state='OPEN' for update",String.class,code);
        if(ids.isEmpty()) throw notFound();
        String id=ids.getFirst();
        if(db.queryForObject("select count(*) from collaboration_members where meeting_id=? and username=?",Integer.class,id,actor)==0) {
            if(db.queryForObject("select count(*) from collaboration_members where meeting_id=?",Integer.class,id)>=12) throw bad("시연 회의는 최대 12명까지 참여할 수 있습니다");
            db.update("insert into collaboration_members values(?,?,?)",id,actor,Timestamp.from(Instant.now()));
            audit.log(actor,"MEETING_JOINED","meeting:"+id,"코드로 참여"); changed(id);
        }
        return detail(id,actor);
    }
    public List<Member> members(String id,String actor) {
        member(id,actor);
        return db.query("select u.username,u.display_name,u.team from users u join collaboration_members p on p.username=u.username where p.meeting_id=? order by p.joined_at",(r,i)->new Member(r.getString(1),r.getString(2),r.getString(3)),id);
    }
    public MeetingMedia.Grant grant(String id,String actor) {
        member(id,actor); openLocked(id);
        var u=users.findByUsername(actor).orElseThrow(this::notFound);
        var grant=media.grant(id,actor,u.getDisplayName());
        audit.log(actor,"MEETING_MEDIA_GRANT","meeting:"+id,"30초 입장 토큰 발급");
        return grant;
    }
    public void end(String id,String actor) {
        Meeting m=detail(id,actor);
        if(!m.host().equals(actor)) throw new ApiException(HttpStatus.FORBIDDEN,"회의를 만든 사람만 종료할 수 있습니다");
        db.queryForList("select id from collaboration_meetings where id=? for update",String.class,id);
        // Retry media deletion even if DB already ended, so temporary outages are recoverable.
        media.end(id);
        db.update("update collaboration_meetings set state='ENDED',ended_at=? where id=? and state='OPEN'",Timestamp.from(Instant.now()),id);
        audit.log(actor,"MEETING_ENDED","meeting:"+id,"회의 종료"); changed(id);
    }
    public List<Message> messages(String id,String actor) {
        member(id,actor);
        var result=db.query("select * from collaboration_messages where meeting_id=? order by id desc limit 100",(r,i)->new Message(r.getLong("id"),r.getString("username"),r.getString("display_name"),r.getString("content"),r.getTimestamp("sent_at").toInstant()),id);
        Collections.reverse(result);return result;
    }
    public void send(String id,String content,String actor) {
        member(id,actor);openLocked(id);
        if(content==null || content.isBlank() || content.length()>1000) throw bad("메시지는 1~1,000자로 입력하세요");
        if(db.queryForObject("select count(*) from collaboration_messages where meeting_id=? and username=? and sent_at>?",Integer.class,id,actor,Timestamp.from(Instant.now().minusSeconds(10)))>=8)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"잠시 후 다시 보내 주세요");
        var u=users.findByUsername(actor).orElseThrow(this::notFound);
        db.update("insert into collaboration_messages(meeting_id,username,display_name,content,sent_at) values(?,?,?,?,?)",id,actor,u.getDisplayName(),content.strip(),Timestamp.from(Instant.now()));
        changed(id);
    }
    private void member(String id,String actor) {
        if(db.queryForObject("select count(*) from collaboration_members where meeting_id=? and username=?",Integer.class,id,actor)==0) throw notFound();
    }
    private void openLocked(String id) {
        var state=db.queryForList("select state from collaboration_meetings where id=? for update",String.class,id);
        if(state.isEmpty() || !state.getFirst().equals("OPEN")) throw new ApiException(HttpStatus.CONFLICT,"종료된 회의입니다");
    }
    private void changed(String id) {
        var recipients=new HashSet<>(db.queryForList("select username from collaboration_members where meeting_id=?",String.class,id));
        events.publishEvent(UserEvent.to(recipients,"meeting.changed",Map.of("id",id)));
    }
    private ApiException notFound() {return new ApiException(HttpStatus.NOT_FOUND,"참여 가능한 회의를 찾을 수 없습니다");}
    private ApiException bad(String s) {return new ApiException(HttpStatus.BAD_REQUEST,s);}
}
