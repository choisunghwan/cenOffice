package com.cenoffice.hub;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 시스템이 넣은 스킬(내장 규칙 스킬·기본 AI 스킬)의 제작자를 실제 계정으로 볼지 정한다.
 * app.hub.system-owner(HUB_SKILL_OWNER)에 아이디를 넣으면 그 계정이 이 스킬들의 제작자처럼 다뤄진다:
 * 내 스킬 목록·수정·제작자 통계·제작자 표시. DB 의 owner 값은 바꾸지 않는다(내장 스킬 판별·자동 업데이트가 owner 로 동작하므로).
 */
@Component
public class SkillOwnership {
    static final List<String> SYSTEM = List.of(BuiltinSkillSeeder.OWNER, OfficialSkillSeeder.OWNER);
    private final String systemOwner;

    public SkillOwnership(@Value("${app.hub.system-owner:${HUB_SKILL_OWNER:}}") String systemOwner) {
        this.systemOwner = systemOwner == null ? "" : systemOwner.strip();
    }

    public boolean system(String owner) { return SYSTEM.contains(owner); }

    /** 화면·권한 판단에 쓰는 제작자 아이디 */
    public String effective(String owner) { return system(owner) && !systemOwner.isEmpty() ? systemOwner : owner; }

    public boolean owns(String owner, String me) { return me != null && effective(owner).equals(me); }

    /** DB 에서 'me 가 만든 스킬'을 찾을 때 쓸 owner 값들 */
    public List<String> ownersOf(String me) {
        if (me == null) return List.of();
        if (!systemOwner.isEmpty() && systemOwner.equals(me)) return List.of(me, BuiltinSkillSeeder.OWNER, OfficialSkillSeeder.OWNER);
        return List.of(me);
    }
}
