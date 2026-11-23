package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cenoffice.common.domain.Enums.Role;
import com.cenoffice.common.domain.UserAccount;
import com.cenoffice.common.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:hubext;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.seed.admin-password=test-admin-pw-1234",
        "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456"})
@AutoConfigureMockMvc
class ExtApiTest {
    static final String EXT_ORIGIN = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    record Acct(String name, String jwt) {}

    Acct account() throws Exception {
        String name = "x" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new UserAccount(name, encoder.encode("pw-" + name), "EXT-" + name, Role.USER));
        String jwt = json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + name + "\",\"password\":\"pw-" + name + "\"}"))
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        return new Acct(name, jwt);
    }

    String token(Acct a, String scope) throws Exception {
        String body = scope == null ? "{\"name\":\"t\"}" : "{\"name\":\"t\",\"scope\":\"" + scope + "\"}";
        return json.readTree(mvc.perform(post("/api/hub/tokens").header("Authorization", "Bearer " + a.jwt()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("secret").asText();
    }

    @Test void tokenScopesAreNotInterchangeable() throws Exception {
        Acct a = account();
        String mcp = token(a, null), navi = token(a, "NAVI");
        mvc.perform(get("/api/ext/tools").header("Authorization", "Bearer " + mcp)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ext/tools").header("Authorization", "Bearer " + navi)).andExpect(status().isOk());
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + navi).contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ext/tools")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ext/tools").header("Authorization", "Bearer " + a.jwt())).andExpect(status().isUnauthorized()); // 로그인 JWT 도 못 쓴다
        mvc.perform(post("/api/hub/tokens").header("Authorization", "Bearer " + a.jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"t\",\"scope\":\"ADMIN\"}")).andExpect(status().isBadRequest());
    }

    @Test void onlyExtensionOriginsAreAccepted() throws Exception {
        String navi = token(account(), "NAVI");
        mvc.perform(get("/api/ext/tools").header("Authorization", "Bearer " + navi).header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api/ext/tools").header("Authorization", "Bearer " + navi).header("Origin", "http://localhost:5180")).andExpect(status().isForbidden());
        mvc.perform(get("/api/ext/tools").header("Authorization", "Bearer " + navi).header("Origin", EXT_ORIGIN)).andExpect(status().isOk());
    }

    @Test void runsAnApprovedToolAndRecordsTheCenNaviOrigin() throws Exception {
        Acct a = account();
        String navi = token(a, "NAVI");
        JsonNode tools = json.readTree(mvc.perform(get("/api/ext/tools").header("Authorization", "Bearer " + navi).header("Origin", EXT_ORIGIN)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        boolean found = false;
        for (JsonNode t : tools) if (t.get("name").asText().equals("cen-log-analyzer")) found = true;
        assertTrue(found);

        String input = "java.lang.IllegalStateException: boom " + UUID.randomUUID() + "\n\tat com.foo.Bar.run(Bar.java:42)";
        String body = json.writeValueAsString(java.util.Map.of("tool", "cen-log-analyzer", "input", input, "host", "Forum.Example.com"));
        JsonNode started = json.readTree(mvc.perform(post("/api/ext/executions").header("Authorization", "Bearer " + navi).header("Origin", EXT_ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String id = started.get("id").asText();
        assertEquals("CENNAVI", started.get("source").asText());
        assertEquals("forum.example.com", started.get("sourceHost").asText());

        JsonNode done = started;
        for (int i = 0; i < 50 && !"SUCCEEDED".equals(done.get("status").asText()); i++) {
            Thread.sleep(200);
            done = json.readTree(mvc.perform(get("/api/ext/executions/" + id).header("Authorization", "Bearer " + navi)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
        assertEquals("SUCCEEDED", done.get("status").asText());
        assertTrue(done.get("result").asText().contains("IllegalStateException"));

        // CEN HUB 웹의 실행 이력에도 같은 작업이 출처와 함께 보인다
        JsonNode hist = json.readTree(mvc.perform(get("/api/hub/executions").header("Authorization", "Bearer " + a.jwt())).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        boolean listed = false;
        for (JsonNode e : hist.get("items")) if (e.get("id").asText().equals(id) && e.get("source").asText().equals("CENNAVI")) listed = true;
        assertTrue(listed);

        // 다른 사람의 실행은 조회할 수 없다
        String other = token(account(), "NAVI");
        mvc.perform(get("/api/ext/executions/" + id).header("Authorization", "Bearer " + other)).andExpect(status().is4xxClientError());
    }

    @Test void rejectsUnknownToolsAndBadHosts() throws Exception {
        String navi = token(account(), "NAVI");
        mvc.perform(post("/api/ext/executions").header("Authorization", "Bearer " + navi).contentType(MediaType.APPLICATION_JSON).content("{\"tool\":\"nope\",\"input\":\"x\"}")).andExpect(status().isNotFound());
        assertNull(ExtController.host("https://a.example/path?q=1"));
        assertNull(ExtController.host("user@evil.example"));
        assertEquals("a.example", ExtController.host(" A.Example "));
    }
}
