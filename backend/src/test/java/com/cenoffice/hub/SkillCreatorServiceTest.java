package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.cenoffice.common.api.ApiException;
import com.cenoffice.common.llm.LlmProvider;
import com.cenoffice.hub.HubDtos.CategoryView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 스킬 만들기 도우미: 질문만 할 때는 초안 없음, 초안은 실제 등록 검사기로 검사, AI 가 없으면 파일 등록 안내 */
class SkillCreatorServiceTest {
    final ObjectMapper om = new ObjectMapper();
    final SkillService skills = mock(SkillService.class);
    { when(skills.categories()).thenReturn(List.of(new CategoryView("doc", "문서"), new CategoryView("etc", "기타"))); }

    static final String GOOD = """
        ---
        name: weekly-report-writer
        title: 주간 보고서 작성
        description: 이번 주 한 일 메모를 넣으면 팀 양식의 주간 보고서 초안을 만듭니다.
        version: 1.0.0
        category: doc
        tags: [보고서, 주간]
        icon: 📝
        ---
        # 주간 보고서 작성
        ## 진행 방법
        1. 메모를 항목별로 나눕니다.
        """;

    SkillCreatorService svc(LlmProvider llm) { return new SkillCreatorService(llm, new SkillPackageReader(om), skills, om); }

    @Test void asksQuestionsThenReturnsACheckedDraft() throws Exception {
        var llm = mock(LlmProvider.class);
        when(llm.isReal()).thenReturn(true);
        when(llm.generateJson(anyString(), anyString())).thenReturn("{\"reply\":\"보통 어떤 자료를 넣으세요?\",\"skillMd\":\"\"}");
        var r = svc(llm).assist("u", new SkillCreatorService.Request(List.of(new SkillCreatorService.Message("user", "주간 보고서 쓰는 거")), null));
        assertEquals("보통 어떤 자료를 넣으세요?", r.reply());
        assertNull(r.skillMd());
        var sys = ArgumentCaptor.forClass(String.class);
        verify(llm).generateJson(sys.capture(), anyString());
        assertTrue(sys.getValue().contains("doc(문서)"), "카테고리 목록을 알려 준다");

        doReturn(om.writeValueAsString(java.util.Map.of("reply", "초안을 만들었어요.", "skillMd", "```markdown\n" + GOOD + "```"))).when(llm).generateJson(anyString(), anyString());
        var d = svc(llm).assist("u", new SkillCreatorService.Request(List.of(new SkillCreatorService.Message("user", "메모를 넣어요")), null));
        assertNotNull(d.skillMd());
        assertTrue(d.skillMd().startsWith("---"), "코드 블록 표시는 벗긴다");
        assertNull(d.error());
    }

    @Test void invalidDraftComesBackWithTheReason() {
        var r = svc(mock(LlmProvider.class)).check("{\"reply\":\"고쳤어요\",\"skillMd\":\"---\\nname: my-skill\\ndescription: 설명입니다\\nversion: abc\\n---\\n본문\"}");
        assertNotNull(r.skillMd());
        assertNotNull(r.error(), "등록 규칙에 안 맞으면 이유를 알려 준다");
        assertThrows(ApiException.class, () -> svc(mock(LlmProvider.class)).check("엉뚱한 답"));
    }

    @Test void withoutAiItPointsToFileUpload() {
        var llm = mock(LlmProvider.class);
        when(llm.isReal()).thenReturn(false);
        var r = svc(llm).assist("u", new SkillCreatorService.Request(List.of(), null));
        assertFalse(r.ai());
        assertTrue(r.reply().contains("파일로 등록"));
    }
}
