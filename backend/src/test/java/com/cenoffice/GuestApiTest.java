package com.cenoffice;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cenoffice.common.api.GuestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 바로 체험하기: 기기마다 방문자 계정, 같은 IP 로 여러 명이 들어와도 되고, 등록·관리 기능은 막히며, 오래되면 지워진다 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:guesttest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock", "livekit.api-key=", "livekit.api-secret="})
@AutoConfigureMockMvc
class GuestApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired GuestService guests;

    JsonNode read(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    JsonNode enter(Object body, String ip) throws Exception {
        var b = post("/api/auth/guest").with(x -> { x.setRemoteAddr(ip); return x; });
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return read(mvc.perform(b).andExpect(status().isOk()));
    }
    ResultActions as(String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) throws Exception { return mvc.perform(b.header("Authorization", "Bearer " + token)); }

    @Test void oneClickGuestCanLookAroundButNotPublish() throws Exception {
        JsonNode g = enter(null, "10.50.0.1");
        String token = g.at("/login/token").asText(), user = g.at("/login/user/username").asText(), key = g.path("key").asText();
        assertTrue(user.startsWith("guest-"));
        assertTrue(g.at("/login/user/displayName").asText().startsWith("방문자 "));
        assertTrue(g.at("/login/user/guest").asBoolean());
        assertTrue(read(as(token, get("/api/auth/me")).andExpect(status().isOk())).path("guest").asBoolean());

        // 같은 기기(저장해 둔 열쇠)로 다시 들어오면 같은 계정, 틀린 열쇠면 새 계정
        assertEquals(user, enter(Map.of("username", user, "key", key), "10.50.0.1").at("/login/user/username").asText());
        assertNotEquals(user, enter(Map.of("username", user, "key", "wrong"), "10.50.0.1").at("/login/user/username").asText());

        // 볼 수는 있고, 등록·토큰·관리는 막힌다
        as(token, get("/api/hub/skills")).andExpect(status().isOk());
        as(token, get("/api/assistant/skills")).andExpect(status().isOk());
        String md = "---\nname: guest-skill\ndescription: 방문자가 올리려는 스킬\n---\n# x\n";
        String blocked = as(token, post("/api/hub/skills").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("skillMd", md))))
            .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(blocked.contains("방문자는 쓸 수 없는 기능"));
        as(token, post("/api/hub/tokens").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"t\"}")).andExpect(status().isForbidden());
        as(token, post("/api/hub/skills/assist").contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[]}")).andExpect(status().isForbidden());

        // 동료 찾기(참석자 초대)에는 방문자가 나오지 않는다
        String admin = read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"test-admin-pw-1234\"}"))).path("token").asText();
        for (JsonNode p : read(as(admin, get("/api/calendar/people")).andExpect(status().isOk()))) assertFalse(p.path("username").asText().startsWith("guest-"));
    }

    @Test void manyVisitorsFromOneVenueIpCanEnter() throws Exception {
        var names = new HashSet<String>();
        for (int i = 0; i < 30; i++) names.add(enter(null, "10.60.0.1").at("/login/user/username").asText());
        assertEquals(30, names.size(), "같은 와이파이(IP)에서도 사람마다 따로 계정");
    }

    @Test void oldGuestsAreRemovedAndTheirTokensStopWorking() throws Exception {
        JsonNode g = enter(null, "10.70.0.1");
        String token = g.at("/login/token").asText();
        as(token, get("/api/auth/me")).andExpect(status().isOk());
        assertTrue(guests.removeOlderThan(Instant.now().plusSeconds(60)) >= 1);
        as(token, get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
}
