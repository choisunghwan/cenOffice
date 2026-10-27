package com.cenoffice.hub;

import com.cenoffice.api.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 스킬의 결과물 양식(hwpx) 저장·조회. 양식 파일은 DB 에 두고 서버 디스크에는 쓰지 않는다. */
@Service
public class TemplateService {
    public record Info(String fileName, String format, List<HwpxTemplate.Field> fields) {}

    private final JdbcTemplate db;
    private final SkillService skills;
    private final ObjectMapper json;

    public TemplateService(JdbcTemplate db, SkillService skills, ObjectMapper json) {
        this.db = db; this.skills = skills; this.json = json;
    }

    /** 제작자·관리자만 등록한다(스킬 수정 권한과 같다). 같은 스킬에 다시 올리면 교체된다. */
    public Info put(Long skillId, String user, boolean admin, String fileName, byte[] data) {
        skills.editable(skillId, user, admin);
        String name = fileName == null ? "" : fileName.strip();
        if (!name.toLowerCase().endsWith(".hwpx")) throw new ApiException(HttpStatus.BAD_REQUEST, "한글 hwpx 파일만 올릴 수 있습니다. 한글에서 .hwpx 로 저장해 올려 주세요.");
        List<HwpxTemplate.Field> fields = HwpxTemplate.analyze(data);
        if (name.length() > 200) name = name.substring(name.length() - 200);
        try {
            db.update("DELETE FROM hub_skill_templates WHERE skill_id = ?", skillId);
            db.update("INSERT INTO hub_skill_templates (skill_id, file_name, format, data, fields, created_by, created_at) VALUES (?, ?, 'hwpx', ?, ?, ?, ?)",
                skillId, name, data, json.writeValueAsString(fields), user, Timestamp.from(Instant.now()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "양식을 저장하지 못했습니다.");
        }
        return new Info(name, "hwpx", fields);
    }

    /** 이 사용자가 볼 수 있는 스킬의 양식 정보. 양식이 없으면 비어 있다. */
    public Optional<Info> info(Long skillId, String user, boolean admin) {
        skills.visible(skillId, user, admin);
        return find(skillId);
    }

    /** 권한 확인 없이 양식 정보를 찾는다(이미 권한을 확인한 호출자용) */
    public Optional<Info> find(Long skillId) {
        var rows = db.query("SELECT file_name, format, fields FROM hub_skill_templates WHERE skill_id = ?", (rs, i) -> {
            try {
                return new Info(rs.getString(1), rs.getString(2), json.readValue(rs.getString(3), new TypeReference<List<HwpxTemplate.Field>>() {}));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }, skillId);
        return rows.stream().findFirst();
    }

    public byte[] data(Long skillId) {
        var rows = db.query("SELECT data FROM hub_skill_templates WHERE skill_id = ?", (rs, i) -> rs.getBytes(1), skillId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "이 도구에는 등록된 양식이 없습니다.");
        return rows.get(0);
    }

    public void delete(Long skillId, String user, boolean admin) {
        skills.editable(skillId, user, admin);
        db.update("DELETE FROM hub_skill_templates WHERE skill_id = ?", skillId);
    }
}
