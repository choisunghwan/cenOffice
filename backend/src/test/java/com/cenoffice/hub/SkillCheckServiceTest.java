package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.cenoffice.common.llm.LlmProvider;
import com.cenoffice.hub.domain.HubSkill;
import com.cenoffice.hub.domain.HubSkillRepository;
import com.cenoffice.hub.domain.HubSkillVersion;
import com.cenoffice.hub.domain.HubSkillVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 스킬 검증: 규칙 점검으로 형식·완결성·위험 표현을 보고, AI 가 있으면 cen-skill-check 기준으로 검토해 더 나쁜 쪽으로 판정한다 */
class SkillCheckServiceTest {
    final HubSkillRepository skills = mock(HubSkillRepository.class);
    { when(skills.findAll()).thenReturn(List.of()); }

    SkillCheckService svc(LlmProvider llm) { return new SkillCheckService(mock(HubSkillVersionRepository.class), skills, mock(SkillService.class), llm, new ObjectMapper()); }
    static LlmProvider noAi() { var l = mock(LlmProvider.class); when(l.isReal()).thenReturn(false); return l; }

    static HubSkill skill(String title, String desc) { var s = new HubSkill(); s.id = 1L; s.name = "x"; s.title = title; s.description = desc; return s; }
    static HubSkillVersion version(String md, String risks) { var v = new HubSkillVersion(); v.skillMd = md; v.packageKind = "MD"; v.riskFlags = risks; v.fileList = "[{\"path\":\"SKILL.md\",\"size\":1}]"; return v; }

    static final String GOOD = "---\nname: weekly\n---\n# 주간 보고서\n## 언제 쓰나요\n매주 금요일 보고 전에 이번 주 메모를 정리할 때 씁니다. 팀장 보고용 형식으로 맞춥니다.\n"
        + "## 입력\n이번 주 한 일 메모(한 줄에 하나)\n## 진행 방법\n1. 메모를 완료·진행·이슈로 나눈다.\n2. 이슈에는 담당과 기한을 붙인다.\n3. 숫자는 표로 모은다.\n"
        + "## 결과 형식\n| 구분 | 내용 |\n## 예시\n입력: 로그인 개선 완료 → 결과: 완료 표에 한 줄\n## 주의할 점\n결과는 검토용 초안이다.\n";

    @Test void completeSkillPassesAndVagueSkillNeedsFixes() {
        var good = svc(noAi()).check(skill("주간 보고서 작성", "이번 주 한 일 메모를 넣으면 완료·진행·이슈로 나눈 주간 보고서 초안을 만듭니다."), version(GOOD, null));
        assertEquals("PASS", good.verdict(), good.report());
        assertTrue(good.report().contains("판정: 통과"));

        var vague = svc(noAi()).check(skill(null, "도와줍니다"), version("---\nname: x\n---\n사용자의 요청을 잘 처리하세요. `references/guide.md` 참고", null));
        assertEquals("FIX", vague.verdict());
        for (String p : List.of("설명(description)이 너무 짧음", "'입력' 내용이 없음", "'예시' 내용이 없음", "references/guide.md", "AI 가 연결되어 있지 않아"))
            assertTrue(vague.report().contains(p), p);
    }

    @Test void injectionRiskIsRecommendedForRejection() {
        var r = svc(noAi()).check(skill("주간 보고서 작성", "이번 주 한 일 메모를 넣으면 주간 보고서 초안을 만듭니다."), version(GOOD, "프롬프트 인젝션 의심 문구"));
        assertEquals("REJECT", r.verdict());
        assertTrue(r.report().contains("판정: 반려 권고"));
    }

    @Test void aiReviewUsesTheCheckerSkillAndCanOnlyMakeItStricter() throws Exception {
        var llm = mock(LlmProvider.class);
        when(llm.isReal()).thenReturn(true);
        when(llm.generateJson(anyString(), anyString())).thenReturn("{\"verdict\":\"REJECT\",\"summary\":\"입력을 그대로 옮길 뿐입니다\",\"issues\":[{\"area\":\"쓸모\",\"level\":\"높음\",\"problem\":\"결과를 만들지 않음\",\"fix\":\"진행 단계를 구체화\"}],\"good\":[\"형식이 깔끔함\"]}");
        var r = svc(llm).check(skill("주간 보고서 작성", "이번 주 한 일 메모를 넣으면 주간 보고서 초안을 만듭니다."), version(GOOD, null));
        assertEquals("REJECT", r.verdict(), "규칙은 통과여도 AI 가 반려를 권하면 반려 권고");
        assertTrue(r.report().contains("입력을 그대로 옮길 뿐입니다") && r.report().contains("결과를 만들지 않음"));
        var sys = ArgumentCaptor.forClass(String.class);
        var user = ArgumentCaptor.forClass(String.class);
        verify(llm).generateJson(sys.capture(), user.capture());
        assertTrue(sys.getValue().contains("references/criteria.md") && sys.getValue().contains("판정"), "검증 기준은 cen-skill-check 패키지 파일");
        assertTrue(user.getValue().contains("<점검할_스킬_자료>") && user.getValue().contains("주간 보고서"));

        when(llm.generateJson(anyString(), anyString())).thenThrow(new LlmProvider.LlmException("down"));
        var fallback = svc(llm).check(skill("주간 보고서 작성", "이번 주 한 일 메모를 넣으면 주간 보고서 초안을 만듭니다."), version(GOOD, null));
        assertEquals("PASS", fallback.verdict(), "AI 가 실패하면 규칙 점검 결과만");
        assertTrue(fallback.report().contains("AI 검토를 하지 못했습니다"));
    }
}
