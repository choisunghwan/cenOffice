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
        "spring.datasource.url=jdbc:h2:mem:hubmcp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.seed.admin-password=test-admin-pw-1234",
        "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456"})
@AutoConfigureMockMvc
class McpApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    record Acct(String name, String jwt) {}

    Acct account() throws Exception {
        String name = "m" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new UserAccount(name, encoder.encode("pw-" + name), "MCP-" + name, Role.USER));
        String jwt = json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + name + "\",\"password\":\"pw-" + name + "\"}"))
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        return new Acct(name, jwt);
    }

    JsonNode createToken(Acct a) throws Exception {
        return json.readTree(mvc.perform(post("/api/hub/tokens").header("Authorization", "Bearer " + a.jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"테스트\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    JsonNode rpc(String token, String body) throws Exception {
        return json.readTree(mvc.perform(post("/mcp").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test void tokenLifecycleAndToolCall() throws Exception {
        Acct a = account();
        JsonNode created = createToken(a);
        String secret = created.get("secret").asText();
        assertTrue(secret.startsWith("cen_pat_"));
        // 목록에는 원문이 없다
        String list = mvc.perform(get("/api/hub/tokens").header("Authorization", "Bearer " + a.jwt())).andReturn().getResponse().getContentAsString();
        assertFalse(list.contains(secret));

        JsonNode init = rpc(secret, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertEquals("cen-ai-hub", init.at("/result/serverInfo/name").asText());
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + secret).contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).andExpect(status().isAccepted());

        JsonNode tools = rpc(secret, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").at("/result/tools");
        boolean found = false;
        for (JsonNode t : tools) if (t.get("name").asText().equals("cen-sql-reviewer")) found = true;
        assertTrue(found);

        String input = "DELETE FROM t; -- " + UUID.randomUUID();
        JsonNode call = rpc(secret, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"cen-sql-reviewer\",\"arguments\":{\"input\":\"" + input + "\"}}}");
        assertFalse(call.at("/result/isError").asBoolean());
        assertTrue(call.at("/result/content/0/text").asText().contains("WHERE 없는 DELETE"));

        JsonNode unknown = rpc(secret, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\",\"arguments\":{\"input\":\"x\"}}}");
        assertTrue(unknown.has("error"));

        // 폐기하면 즉시 401
        mvc.perform(delete("/api/hub/tokens/" + created.at("/token/id").asLong()).header("Authorization", "Bearer " + a.jwt())).andExpect(status().isNoContent());
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + secret).contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}")).andExpect(status().isUnauthorized());
    }

    @Test void promptsMirrorToolsSoTheyShowUpAsSlashCommands() throws Exception {
        String secret = createToken(account()).get("secret").asText();
        assertTrue(rpc(secret, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").at("/result/capabilities").has("prompts"));

        JsonNode prompts = rpc(secret, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/list\"}").at("/result/prompts");
        JsonNode sql = null;
        for (JsonNode p : prompts) if (p.get("name").asText().equals("cen-sql-reviewer")) sql = p;
        assertNotNull(sql);
        assertEquals("input", sql.at("/arguments/0/name").asText());
        assertFalse(sql.at("/arguments/0/required").asBoolean());

        JsonNode got = rpc(secret, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"prompts/get\",\"params\":{\"name\":\"cen-sql-reviewer\",\"arguments\":{\"input\":\"DELETE FROM t\"}}}");
        assertEquals("user", got.at("/result/messages/0/role").asText());
        String text = got.at("/result/messages/0/content/text").asText();
        assertTrue(text.contains("cen-sql-reviewer") && text.contains("DELETE FROM t"));

        assertTrue(rpc(secret, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"prompts/get\",\"params\":{\"name\":\"nope\",\"arguments\":{\"input\":\"x\"}}}").has("error"));
        // 원문 없이도 받을 수 있다(슬래시만 입력) — 원문을 다음 메시지로 기다리게 하는 요청문
        JsonNode bare = rpc(secret, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/get\",\"params\":{\"name\":\"cen-sql-reviewer\",\"arguments\":{}}}");
        assertFalse(bare.has("error"));
        assertTrue(bare.at("/result/messages/0/content/text").asText().contains("다음 메시지"));
    }

    @Test void rejectsMissingBadJwtAndBrowserOriginRequests() throws Exception {
        Acct a = account();
        String secret = createToken(a).get("secret").asText();
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(ping)).andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").header("Authorization", "Bearer cen_pat_deadbeef").contentType(MediaType.APPLICATION_JSON).content(ping)).andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + a.jwt()).contentType(MediaType.APPLICATION_JSON).content(ping)).andExpect(status().isUnauthorized()); // 로그인 JWT 는 MCP 에 쓸 수 없다
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + secret).header("Origin", "http://evil.example").contentType(MediaType.APPLICATION_JSON).content(ping)).andExpect(status().isForbidden());
        mvc.perform(get("/mcp")).andExpect(status().isMethodNotAllowed());
    }

    @Test void tokensAreOwnedByTheirCreator() throws Exception {
        Acct a = account(), b = account();
        long id = createToken(a).at("/token/id").asLong();
        mvc.perform(delete("/api/hub/tokens/" + id).header("Authorization", "Bearer " + b.jwt())).andExpect(status().isNotFound());
    }
}
