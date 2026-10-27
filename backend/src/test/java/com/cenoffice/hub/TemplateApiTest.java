package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cenoffice.hub.DocumentExporter.Block;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 스킬의 결과물 양식(hwpx) 등록·조회·삭제와, 양식 채우기 API */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:templatetest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "app.seed.admin-password=test-admin-pw-1234", "app.seed.user-password=test-user-pw-1234",
    "app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.llm.provider=mock", "livekit.api-key=", "livekit.api-secret="})
@AutoConfigureMockMvc
class TemplateApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String admin, user;
    long skillId;

    @BeforeEach void setup() throws Exception {
        admin = login("admin", "test-admin-pw-1234");
        user = login("user", "test-user-pw-1234");
        var page = read(req(get("/api/hub/skills?status=APPROVED&size=100"), admin, null).andExpect(status().isOk()));
        for (var s : page.path("items")) if (s.path("name").asText().equals("cen-sql-reviewer")) skillId = s.path("id").asLong();
        assertTrue(skillId > 0);
        req(delete(url()), admin, null).andExpect(status().isNoContent()); // 테스트끼리 영향을 주지 않도록 양식을 비운다
    }

    String url() { return "/api/hub/skills/" + skillId + "/template"; }

    String login(String u, String p) throws Exception {
        return read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", u, "password", p))).with(r -> { r.setRemoteAddr("10.8.0.1"); return r; }))).path("token").asText();
    }

    ResultActions req(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    JsonNode read(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    static byte[] form(String... labels) throws Exception {
        var rows = new java.util.ArrayList<List<String>>();
        rows.add(List.of("항목", "내용"));
        for (String l : labels) rows.add(List.of(l, ""));
        return HwpxWriter.write("양식", List.of(new Block("p", 0, "{{사업명}}", null), new Block("table", 0, null, rows)));
    }

    ResultActions upload(String token, String name, byte[] data) throws Exception {
        return req(multipart(HttpMethod.PUT, url()).file(new MockMultipartFile("file", name, "application/octet-stream", data)), token, null);
    }

    @Test void ownerCanUploadAndReadTemplate() throws Exception {
        var put = read(upload(admin, "사업계획서.hwpx", form("성명", "소속")).andExpect(status().isOk()));
        assertEquals("사업계획서.hwpx", put.path("fileName").asText());
        assertEquals("hwpx", put.path("format").asText());
        assertEquals(3, put.path("fields").size());
        var got = read(req(get(url()), admin, null).andExpect(status().isOk()));
        assertEquals("사업명", got.path("fields").get(0).path("key").asText());
        assertEquals("성명", got.path("fields").get(1).path("key").asText());
    }

    @Test void anyViewerCanReadTemplateInfoButNoTemplateIs204() throws Exception {
        req(get(url()), user, null).andExpect(status().isNoContent());
        upload(admin, "a.hwpx", form("성명")).andExpect(status().isOk());
        req(get(url()), user, null).andExpect(status().isOk());
    }

    @Test void reuploadReplaces() throws Exception {
        upload(admin, "a.hwpx", form("성명")).andExpect(status().isOk());
        upload(admin, "b.hwpx", form("주소", "전화")).andExpect(status().isOk());
        var got = read(req(get(url()), admin, null).andExpect(status().isOk()));
        assertEquals("b.hwpx", got.path("fileName").asText());
        assertEquals(3, got.path("fields").size());
    }

    @Test void otherUserGets403() throws Exception {
        upload(user, "a.hwpx", form("성명")).andExpect(status().isForbidden());
        req(delete(url()), user, null).andExpect(status().isForbidden());
    }

    @Test void invalidFileGets400WithKoreanMessage() throws Exception {
        var res = read(upload(admin, "a.hwpx", "그냥 글자".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest()));
        assertTrue(res.toString().contains("hwpx"), res.toString());
        upload(admin, "a.hwp", form("성명")).andExpect(status().isBadRequest()); // 확장자가 .hwpx 가 아니다
        upload(admin, "a.xlsm", form("성명")).andExpect(status().isBadRequest());
    }

    @Test void tooLargeGets400() throws Exception {
        upload(admin, "big.hwpx", new byte[5 * 1024 * 1024 + 1]).andExpect(status().isBadRequest());
    }

    @Test void deleteRemoves() throws Exception {
        upload(admin, "a.hwpx", form("성명")).andExpect(status().isOk());
        req(delete(url()), admin, null).andExpect(status().isNoContent());
        req(get(url()), admin, null).andExpect(status().isNoContent());
    }

    @Test void requiresLogin() throws Exception {
        mvc.perform(get(url())).andExpect(status().isUnauthorized());
    }
}
