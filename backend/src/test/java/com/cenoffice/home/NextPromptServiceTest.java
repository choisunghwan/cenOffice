package com.cenoffice.home;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.cenoffice.common.llm.LlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NextPromptServiceTest {
    final List<AssistantService.HistoryTurn> convo = List.of(
        new AssistantService.HistoryTurn("user", "내일 오후 3시에 팀 회의 잡아줘"),
        new AssistantService.HistoryTurn("assistant", "‘팀 회의’ 일정을 등록했습니다."));

    @Test void aiReadsTheConversationAndSuggestsNextPrompts() throws Exception {
        var llm = mock(LlmProvider.class);
        when(llm.isReal()).thenReturn(true);
        when(llm.chatQuick(anyString(), anyString())).thenReturn("```json\n{\"suggestions\":[\"팀 회의에 김대리 초대해줘\",\"내일 3시 회의실 예약해줘\",\"팀 회의에 김대리 초대해줘\",\"" + "가".repeat(60) + "\",123,\"내일 일정 알려줘\",\"넷째\"]}\n```");
        var s = new NextPromptService(llm, new ObjectMapper());
        assertEquals(List.of("팀 회의에 김대리 초대해줘", "내일 3시 회의실 예약해줘", "내일 일정 알려줘"), s.suggest("u", convo), "중복·너무 긴 것·문자열 아닌 것은 빼고 최대 3개");
        var in = ArgumentCaptor.forClass(String.class);
        verify(llm).chatQuick(anyString(), in.capture());
        assertTrue(in.getValue().contains("내일 오후 3시에 팀 회의 잡아줘"));
        assertTrue(in.getValue().contains("등록했습니다"));
    }

    @Test void withoutAiOrOnFailureItSuggestsNothing() throws Exception {
        var off = mock(LlmProvider.class);
        when(off.isReal()).thenReturn(false);
        assertEquals(List.of(), new NextPromptService(off, new ObjectMapper()).suggest("u", convo));
        verify(off, never()).chatQuick(anyString(), anyString());

        var broken = mock(LlmProvider.class);
        when(broken.isReal()).thenReturn(true);
        when(broken.chatQuick(anyString(), anyString())).thenThrow(new LlmProvider.LlmException("down"));
        assertEquals(List.of(), new NextPromptService(broken, new ObjectMapper()).suggest("u", convo));
        doReturn("제안 없음").when(broken).chatQuick(anyString(), anyString());
        assertEquals(List.of(), new NextPromptService(broken, new ObjectMapper()).suggest("u", convo));
    }
}
