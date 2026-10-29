package com.cenoffice.hub.form;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cenoffice.hub.form.DocumentExporter.Block;
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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
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

    @Test void formatSlotsShowWhichFormatsAreUploadedAndSupported() throws Exception {
        var before = read(req(get(url() + "/formats"), admin, null).andExpect(status().isOk()));
        assertEquals(4, before.size());
        assertEquals("hwpx", before.get(0).path("format").asText());
        assertTrue(before.get(0).path("supported").asBoolean());
        assertTrue(before.get(0).path("template").isMissingNode() || before.get(0).path("template").isNull());
        for (int i = 0; i < 4; i++) assertTrue(before.get(i).path("supported").asBoolean()); // 네 형식 모두 채울 수 있다
        upload(admin, "a.hwpx", form("성명")).andExpect(status().isOk());
        var after = read(req(get(url() + "/formats"), admin, null).andExpect(status().isOk()));
        assertEquals("a.hwpx", after.get(0).path("template").path("fileName").asText());
        assertTrue(after.get(2).path("template").isMissingNode() || after.get(2).path("template").isNull());
    }

    @Test void brokenOfficeFilesGetClearMessage() throws Exception {
        var res = read(upload(admin, "a.xlsx", new byte[]{1, 2, 3}).andExpect(status().isBadRequest()));
        assertTrue(res.toString().contains("엑셀"), res.toString());
        upload(admin, "a.txt", new byte[]{1}).andExpect(status().isBadRequest()); // 모르는 확장자
    }

    /** 내 양식: 도구 제작자·관리자가 아니어도 자기 양식을 올리고 쓸 수 있고, 다른 사람에게는 보이지 않는다 */
    @Test void myFormIsPrivateToItsOwner() throws Exception {
        String me = "/api/hub/templates/me";
        req(delete(me), user, null).andExpect(status().isNoContent());
        req(delete(me), admin, null).andExpect(status().isNoContent());
        req(multipart(HttpMethod.PUT, me).file(new MockMultipartFile("file", "내양식.hwpx", "application/octet-stream", form("성명"))), user, null).andExpect(status().isOk());
        assertEquals("내양식.hwpx", read(req(get(me), user, null).andExpect(status().isOk())).path("fileName").asText());
        req(get(me), admin, null).andExpect(status().isNoContent()); // 다른 사람의 '내 양식'에는 보이지 않는다
        req(get(url()), admin, null).andExpect(status().isNoContent()); // 도구 양식에도 섞이지 않는다
        var plan = read(req(post(me + "/plan"), user, Map.of("format", "hwpx", "result", "성명: 홍길동")).andExpect(status().isOk()));
        assertEquals("홍길동", item(plan, "성명").path("value").asText());
        req(post(me + "/render"), user, Map.of("format", "hwpx", "values", Map.of("성명", "홍길동"), "title", "회의록")).andExpect(status().isOk());
        req(post(me + "/plan"), admin, Map.of("format", "hwpx", "result", "성명: 홍길동")).andExpect(status().isNotFound()); // 관리자도 남의 양식은 쓸 수 없다
        req(get("/api/hub/skills/-999999/template/formats"), user, null).andExpect(status().isNotFound()); // 남의 번호로 직접 접근해도 막힌다
        req(delete(me), user, null).andExpect(status().isNoContent());
        req(get(me), user, null).andExpect(status().isNoContent());
    }

    /** 예전에 저장된 항목 목록(번호 칸이 항목으로 들어간 것)도 읽을 때 새 규칙으로 다시 찾는다 */
    @Test void storedFieldListsAreRecomputedWithCurrentRules() throws Exception {
        upload(admin, "a.hwpx", form("성명")).andExpect(status().isOk());
        jdbc.update("UPDATE hub_skill_templates SET fields = ? WHERE skill_id = ? AND format = 'hwpx'", "[{\"key\":\"1\",\"label\":\"1\",\"kind\":\"cell\"},{\"key\":\"2\",\"label\":\"2\",\"kind\":\"cell\"}]", skillId);
        var got = read(req(get(url()), admin, null).andExpect(status().isOk()));
        assertEquals("사업명", got.path("fields").get(0).path("key").asText());
        assertEquals("성명", got.path("fields").get(1).path("key").asText());
        assertEquals(2, got.path("fields").size());
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

    // ───────────── 채우기 계획·초안·미리보기·파일 생성 ─────────────

    JsonNode postJson(String path, String token, Object body) throws Exception { return read(req(post(url() + path), token, body).andExpect(status().isOk())); }

    JsonNode item(JsonNode plan, String key) {
        for (var i : plan.path("items")) if (i.path("key").asText().equals(key)) return i;
        fail("항목이 없습니다: " + key);
        return null;
    }

    @Test void planFillsFromLabelLinesWithoutAi() throws Exception {
        upload(admin, "a.hwpx", form("성명", "소속")).andExpect(status().isOk());
        var plan = postJson("/plan", admin, Map.of("result", "# 사업명\nAI 업무 도우미\n\n성명: 홍길동\n"));
        assertFalse(plan.path("aiUsed").asBoolean());
        assertEquals("AI 업무 도우미", item(plan, "사업명").path("value").asText());
        assertEquals("RESULT", item(plan, "사업명").path("source").asText());
        assertEquals("홍길동", item(plan, "성명").path("value").asText());
        assertEquals("MISSING", item(plan, "소속").path("source").asText());
        assertTrue(item(plan, "소속").path("value").isMissingNode() || item(plan, "소속").path("value").isNull()); // 서버는 null 필드를 빼고 보낸다
    }

    /** 내장 회의록 도구가 내는 모양(표 + 제목 아래 본문 + 액션 표)을 그대로 읽는다 */
    @Test void planReadsTablesAndAliasesFromMeetingMinutesTool() throws Exception {
        upload(admin, "m.hwpx", form("회의명", "일시", "참석자", "결정 사항", "액션 아이템", "논의 내용")).andExpect(status().isOk());
        String result =
                "# 회의록 정리 초안\n"
                + "\n"
                + "| 항목 | 내용 |\n"
                + "|---|---|\n"
                + "| 회의 제목 | 주간 팀 회의 |\n"
                + "| 일시 | 2026-10-28 |\n"
                + "| 참석자 | 김대리, 박과장 |\n"
                + "\n"
                + "## 결정 사항\n"
                + "- 견적서는 금요일까지 확정하기로 함\n"
                + "\n"
                + "## 액션 아이템\n"
                + "| No | 내용 | 담당 | 기한(원문 표현) |\n"
                + "|---|---|---|---|\n"
                + "| 1 | 견적서 작성 | 김대리 | 금요일 |\n"
                + "\n"
                + "## 논의 내용\n"
                + "- 일정 공유\n"
                + "\n";
        var plan = postJson("/plan", admin, Map.of("result", result));
        assertEquals("주간 팀 회의", item(plan, "회의명").path("value").asText()); // '회의 제목'을 '회의명'으로 찾는다
        assertEquals("2026-10-28", item(plan, "일시").path("value").asText());
        assertEquals("김대리, 박과장", item(plan, "참석자").path("value").asText());
        assertTrue(item(plan, "결정 사항").path("value").asText().contains("금요일까지 확정"));
        String action = item(plan, "액션 아이템").path("value").asText();
        assertTrue(action.contains("1. 견적서 작성 (김대리 · 금요일)"), action);
        assertFalse(action.contains("|---"), action); // 표 구분선이 칸에 섞이지 않는다
        assertEquals("RESULT", item(plan, "액션 아이템").path("source").asText());
    }

    /** 이름이 겹치는 항목("일시 (2)": 다음 회의 일시 등)은 어느 값인지 알 수 없어 결과에서 자동으로 채우지 않는다 */
    @Test void repeatedLabelsAreNotAutoFilledFromTheResult() throws Exception {
        upload(admin, "a.hwpx", form("일시", "일시")).andExpect(status().isOk());
        var plan = postJson("/plan", admin, Map.of("format", "hwpx", "result", "일시: 2026-10-27"));
        assertEquals("2026-10-27", item(plan, "일시").path("value").asText());
        assertEquals("MISSING", item(plan, "일시 (2)").path("source").asText());
    }

    @Test void planNeverInventsValues() throws Exception {
        upload(admin, "a.hwpx", form("성명", "소속")).andExpect(status().isOk());
        var plan = postJson("/plan", admin, Map.of("result", "오늘 날씨가 좋습니다. 관련 없는 문장입니다."));
        for (var i : plan.path("items")) assertEquals("MISSING", i.path("source").asText(), i.toString());
    }

    @Test void previewReflectsValues() throws Exception {
        upload(admin, "a.hwpx", form("성명")).andExpect(status().isOk());
        var res = postJson("/preview", admin, Map.of("values", Map.of("성명", "홍")));
        boolean found = false;
        for (var b : res.path("blocks")) if (b.path("type").asText().equals("table")) found = b.path("rows").get(1).get(1).path("text").asText().equals("홍");
        assertTrue(found, res.toString());
    }

    @Test void renderReturnsValidHwpxWithValues() throws Exception {
        upload(admin, "a.hwpx", form("성명")).andExpect(status().isOk());
        var r = req(post(url() + "/render"), admin, Map.of("values", Map.of("성명", "홍길동"), "title", "사업계획서")).andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(r.getHeader("Content-Disposition").contains("filename"));
        var blocks = HwpxTemplate.preview(r.getContentAsByteArray(), Map.of());
        boolean found = false;
        for (var b : blocks) if (b.type().equals("table")) found = b.rows().get(1).get(1).text().equals("홍길동");
        assertTrue(found);
    }

    @Test void draftNeedsAnAiConnection() throws Exception {
        upload(admin, "a.hwpx", form("성명")).andExpect(status().isOk());
        req(post(url() + "/draft"), admin, Map.of("result", "내용", "key", "성명")).andExpect(status().isBadRequest());
    }

    @Test void planWithoutTemplateGets404() throws Exception {
        req(post(url() + "/plan"), admin, Map.of("result", "내용")).andExpect(status().isNotFound());
    }
}
