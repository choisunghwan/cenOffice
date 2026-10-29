package com.cenoffice.meeting;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cenoffice.common.llm.LlmProvider;
import static org.mockito.Mockito.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:meetingaitest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock",
    "livekit.api-key=", "livekit.api-secret=", "meeting.ai.enabled=true"})
@AutoConfigureMockMvc
class MeetingAssistantApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean LlmProvider llm;
    @MockBean SpeechSynthesizer tts;
    @MockBean MeetingMedia media;
    @Autowired CollaborationService meetings;
    @Autowired MeetingAssistantService assistant;
    String login(String name) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username", name, "password", "test-" + name + "-pw-1234"))))
            .andReturn().getResponse().getContentAsString()).path("token").asText();
    }
    @Test void membersSeeUnavailableConfigurationButOutsidersCannotReadIt() throws Exception {
        String host = login("admin"), guest = login("user");
        JsonNode meeting = json.readTree(mvc.perform(post("/api/meetings").header("Authorization", "Bearer " + host)
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"AI 회의\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String base = "/api/meetings/" + meeting.path("id").asText() + "/assistant";
        var state = json.readTree(mvc.perform(get(base).header("Authorization", "Bearer " + host))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertFalse(state.path("available").asBoolean());
        assertFalse(state.path("enabled").asBoolean());
        mvc.perform(get(base).header("Authorization", "Bearer " + guest)).andExpect(status().isNotFound());
        mvc.perform(post(base+"/stop").header("Authorization", "Bearer " + guest)).andExpect(status().isNotFound());
        mvc.perform(get(base+"/turns/unknown/audio").header("Authorization", "Bearer " + guest)).andExpect(status().isNotFound());
        mvc.perform(post(base+"/turns/unknown/grant").header("Authorization", "Bearer " + guest)).andExpect(status().isNotFound());
        mvc.perform(post(base + "/enable").header("Authorization", "Bearer " + host)
            .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isServiceUnavailable());
    }
    JsonNode call(String method,String path,String token,Object body,int code) throws Exception {
        var req=switch(method){case "POST"->post(path);case "DELETE"->delete(path);default->get(path);};
        req.header("Authorization","Bearer "+token);
        if(body!=null)req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var response=mvc.perform(req).andExpect(status().is(code)).andReturn().getResponse();
        return response.getContentAsByteArray().length==0 ? json.createObjectNode():json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    String[] meeting() throws Exception {
        String h=login("admin"),g=login("user");
        var m=call("POST","/api/meetings",h,Map.of("title","음성 AI"),201);
        call("POST","/api/meetings/join",g,Map.of("code",m.path("code").asText()),200);
        return new String[]{h,g,"/api/meetings/"+m.path("id").asText()};
    }
    void configured() throws Exception {
        when(llm.isReal()).thenReturn(true);when(tts.available()).thenReturn(true);
        when(llm.chat(anyString(),anyString())).thenReturn("제안입니다. 작은 범위부터 검증하세요.");
        when(tts.synthesize(anyString())).thenReturn(GeminiSpeechTest.wave());
    }
    JsonNode waitFor(String base,String token,String expected) throws Exception {
        for(int i=0;i<100;i++){
            var state=call("GET",base+"/assistant",token,null,200);
            if(expected.equals(state.path("turn").path("state").asText()))return state;
            Thread.sleep(30);
        }throw new AssertionError("상태 전환 없음: "+expected);
    }
    @Test void hostControlsAssistantAndAnswerUsesOnlyCurrentlyConsentedSpeech() throws Exception {
        configured();var x=meeting();String h=x[0],g=x[1],b=x[2];
        call("POST",b+"/assistant/enable",g,Map.of(),403);
        call("POST",b+"/assistant/ask",g,Map.of("question","의견은?"),409);
        call("POST",b+"/assistant/enable",h,Map.of(),200);
        for(String token:new String[]{h,g})call("POST",b+"/transcript/consent",token,Map.of(),204);
        call("POST",b+"/transcript",h,Map.of("content","금요일 배포를 검토합니다"),201);
        call("POST",b+"/transcript",g,Map.of("content","비공개로 제외할 발언"),201);
        call("DELETE",b+"/transcript/consent",g,null,204);
        call("POST",b+"/assistant/ask",g,Map.of("question","일정에 대한 의견은?"),202);
        var ready=waitFor(b,h,"READY");
        assertTrue(ready.path("turn").path("answer").asText().contains("제안"));
        var captured=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llm).chat(anyString(),captured.capture());
        assertTrue(captured.getValue().contains("금요일 배포"));
        assertFalse(captured.getValue().contains("비공개로 제외할 발언"));
        String turn=ready.path("turn").path("id").asText();
        call("POST",b+"/assistant/turns/"+turn+"/grant",h,Map.of(),409);
        var audio=mvc.perform(get(b+"/assistant/turns/"+turn+"/audio").header("Authorization","Bearer "+g))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertArrayEquals(GeminiSpeechTest.wave(),audio.getContentAsByteArray());
        call("POST",b+"/assistant/stop",h,Map.of(),200);
        call("POST",b+"/assistant/turns/"+turn+"/playing",g,Map.of(),409);
        call("POST",b+"/assistant/turns/"+turn+"/complete",g,Map.of(),409);
        mvc.perform(get(b+"/assistant/turns/"+turn+"/audio").header("Authorization","Bearer "+g)).andExpect(status().isConflict());
        call("POST",b+"/assistant/disable",h,Map.of(),200);
        call("POST",b+"/assistant/ask",g,Map.of("question","다시"),409);
        call("POST",b+"/end",h,Map.of(),204);
        call("POST",b+"/assistant/enable",h,Map.of(),409);
    }
    @Test void stopDuringGenerationDiscardsLateAudioAndRejectsConcurrentQuestion() throws Exception {
        configured();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(llm.chat(anyString(),anyString())).thenAnswer(i->{entered.countDown();release.await(5,TimeUnit.SECONDS);return "늦은 응답";});
        var x=meeting();String h=x[0],g=x[1],b=x[2];
        call("POST",b+"/assistant/enable",h,Map.of(),200);
        call("POST",b+"/assistant/ask",h,Map.of("question","첫 질문"),202);
        assertTrue(entered.await(3,TimeUnit.SECONDS));
        try {
            call("POST",b+"/assistant/ask",g,Map.of("question","동시 질문"),409);
            call("POST",b+"/assistant/stop",g,Map.of(),200);
        }finally{release.countDown();}
        waitFor(b,h,"STOPPED");
        verify(tts,after(150).never()).synthesize(anyString());
    }
    @Test void ttsFailureKeepsTextAndEndedMeetingRejectsQuestions() throws Exception {
        configured();when(tts.synthesize(anyString())).thenThrow(new IllegalStateException("provider secret"));
        var x=meeting();String h=x[0],b=x[2];
        call("POST",b+"/assistant/enable",h,Map.of(),200);
        call("POST",b+"/assistant/ask",h,Map.of("question","의견은?"),202);
        var state=waitFor(b,h,"TEXT_ONLY");
        assertFalse(state.toString().contains("provider secret"));
        assertFalse(state.path("turn").path("answer").asText().isBlank());
        call("POST",b+"/end",h,Map.of(),204);
        call("POST",b+"/assistant/ask",h,Map.of("question","종료 후"),409);
    }
    @Test void endingMeetingSerializesAssistantTokenIssuance() throws Exception {
        configured();var x=meeting();String h=x[0],b=x[2],id=b.substring(b.lastIndexOf('/')+1);
        call("POST",b+"/assistant/enable",h,Map.of(),200);
        call("POST",b+"/assistant/ask",h,Map.of("question","의견은?"),202);
        String turn=waitFor(b,h,"READY").path("turn").path("id").asText();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{entered.countDown();release.await(5,TimeUnit.SECONDS);return null;}).when(media).end(id);
        when(media.assistantGrant(id,turn)).thenReturn(new MeetingMedia.Grant("ws://localhost","token"));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var ending=pool.submit(()->meetings.end(id,"admin"));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var granting=pool.submit(()->assistant.grant(id,"admin",turn));
            try{Thread.sleep(150);assertFalse(granting.isDone(),"토큰 발급은 진행 중인 회의 종료를 기다려야 합니다");}
            finally{release.countDown();}
            ending.get(3,TimeUnit.SECONDS);
            var e=assertThrows(java.util.concurrent.ExecutionException.class,()->granting.get(3,TimeUnit.SECONDS));
            assertInstanceOf(com.cenoffice.common.api.ApiException.class,e.getCause());
        }
    }
}
