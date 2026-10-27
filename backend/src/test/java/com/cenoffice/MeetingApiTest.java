package com.cenoffice;

import com.cenoffice.meeting.MeetingMedia;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:meetingtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234","app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456","app.llm.provider=mock"})
@AutoConfigureMockMvc
class MeetingApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean MeetingMedia media;
    String host,guest;
    @BeforeEach void setup() throws Exception {
        host=login("admin","test-admin-pw-1234");guest=login("user","test-user-pw-1234");
        when(media.grant(anyString(),anyString(),anyString())).thenReturn(new MeetingMedia.Grant("ws://localhost:7880","test-grant"));
    }
    String login(String u,String p) throws Exception {return read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",u,"password",p)))).andExpect(status().isOk())).path("token").asText();}
    ResultActions req(MockHttpServletRequestBuilder b,String token,Object body) throws Exception {
        b.header("Authorization","Bearer "+token);
        if(body!=null)b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));return mvc.perform(b);
    }
    JsonNode read(ResultActions r) throws Exception {return json.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
    JsonNode create() throws Exception {return read(req(post("/api/meetings"),host,Map.of("title","팀 회의 "+UUID.randomUUID())).andExpect(status().isCreated()));}
    @Test void anonymousAndNonMemberCannotReadOrMintToken() throws Exception {
        mvc.perform(get("/api/meetings")).andExpect(status().isUnauthorized());
        var m=create();String id=m.path("id").asText();
        req(get("/api/meetings/"+id),guest,null).andExpect(status().isNotFound());
        req(get("/api/meetings/"+id+"/messages"),guest,null).andExpect(status().isNotFound());
        req(post("/api/meetings/"+id+"/join-token"),guest,Map.of()).andExpect(status().isNotFound());
        for(var x:read(req(get("/api/meetings"),guest,null)))assertNotEquals(id,x.path("id").asText());
        verify(media,never()).grant(anyString(),anyString(),anyString());
    }
    @Test void codeAdmissionIsIdempotentAndGrantUsesLoggedInIdentity() throws Exception {
        var m=create();String id=m.path("id").asText();
        for(int i=0;i<2;i++)req(post("/api/meetings/join"),guest,Map.of("code",m.path("code").asText())).andExpect(status().isOk());
        assertEquals(2,read(req(get("/api/meetings/"+id),guest,null)).path("memberCount").asInt());
        req(post("/api/meetings/"+id+"/join-token"),guest,Map.of("username","admin")).andExpect(status().isOk());
        verify(media).grant(id,"user","일반 사용자");
    }
    @Test void onlyHostEndsAndEndedMeetingRejectsJoinMessagesAndGrants() throws Exception {
        var m=create();String id=m.path("id").asText();
        req(post("/api/meetings/join"),guest,Map.of("code",m.path("code").asText()));
        req(post("/api/meetings/"+id+"/end"),guest,Map.of()).andExpect(status().isForbidden());
        req(post("/api/meetings/"+id+"/end"),host,Map.of()).andExpect(status().isNoContent());
        verify(media).end(id);
        req(post("/api/meetings/"+id+"/join-token"),host,Map.of()).andExpect(status().isConflict());
        req(post("/api/meetings/"+id+"/messages"),guest,Map.of("content","안녕하세요")).andExpect(status().isConflict());
        req(post("/api/meetings/join"),guest,Map.of("code",m.path("code").asText())).andExpect(status().isNotFound());
    }
    @Test void messagesKeepAccountIdentityAndRateLimit() throws Exception {
        var m=create();String id=m.path("id").asText();
        String content="<script>alert(1)</script>";
        for(int i=0;i<8;i++)req(post("/api/meetings/"+id+"/messages"),host,Map.of("content",content,"username","user")).andExpect(status().isNoContent());
        var msgs=read(req(get("/api/meetings/"+id+"/messages"),host,null));
        assertEquals("admin",msgs.get(0).path("username").asText());assertEquals(content,msgs.get(0).path("content").asText());
        req(post("/api/meetings/"+id+"/messages"),host,Map.of("content","too fast")).andExpect(status().isTooManyRequests());
    }
    @Test void validatesInputAndMediaFailureDoesNotPretendToEndMeeting() throws Exception {
        req(post("/api/meetings"),host,Map.of("title"," ")).andExpect(status().isBadRequest());
        req(post("/api/meetings/join"),guest,Map.of("code","unknown")).andExpect(status().isNotFound());
        var m=create();String id=m.path("id").asText();
        req(post("/api/meetings/"+id+"/messages"),host,Map.of("content","a".repeat(1001))).andExpect(status().isBadRequest());
        doThrow(new com.cenoffice.api.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"unavailable")).when(media).end(id);
        req(post("/api/meetings/"+id+"/end"),host,Map.of()).andExpect(status().isServiceUnavailable());
        assertEquals("OPEN",read(req(get("/api/meetings/"+id),host,null)).path("state").asText());
    }
}
