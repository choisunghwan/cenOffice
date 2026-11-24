package com.cenoffice.hub;

import com.cenoffice.hub.HubDtos.SkillSummary;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 외부 입구(MCP, CenNavi)에 보여 줄 스킬 목록: 승인된 스킬 중 이 사용자가 볼 수 있는 것만(샘플·이름 규칙에 안 맞는 것 제외). */
@Component
public class ExposedTools {
    private final SkillService skills;

    public ExposedTools(SkillService skills) { this.skills = skills; }

    public List<SkillSummary> exposed(PersonalTokenService.Principal who) {
        List<SkillSummary> out = new ArrayList<>();
        int page = 0;
        while (page < 10) {
            var res = skills.search(new SkillService.SearchQuery(null, null, null, null, "APPROVED", false, null, page++, 100), who.username(), who.admin());
            for (SkillSummary s : res.items()) {
                if (s.sample() || !s.name().matches("[A-Za-z0-9_-]{1,64}")) continue;
                out.add(s);
            }
            if (res.items().size() < 100) break;
        }
        return out;
    }
}
