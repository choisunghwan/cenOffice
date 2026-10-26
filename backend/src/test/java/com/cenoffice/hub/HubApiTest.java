package com.cenoffice.hub;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cenoffice.domain.Enums.Role;
import com.cenoffice.domain.UserAccount;
import com.cenoffice.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** AI HUB 통합 테스트 (런타임 미연동 기본 설정) */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:hubtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.seed.admin-password=test-admin-pw-1234",
        "app.seed.user-password=test-user-pw-1234",
        "app.jwt.secret=test-secret-test-secret-test-secret-123456",
        "app.devices.mock.min-latency-ms=100",
        "app.devices.mock.max-latency-ms=150"})
@AutoConfigureMockMvc
class HubApiTest {
    @Test void bundledToolsExecuteUploadedDocumentsAndKeepPrivateHistory() throws Exception {
        var user = account("builtin");
        var stranger = account("other");
        var list = read(call(get("/api/hub/builtins"), user, null).andExpect(status().isOk()));
        assertEquals(com.cenoffice.hub.BuiltinSkillSeeder.DOCUMENT_TOOLS.size(), list.size());
        for (var detail : list) {
            assertFalse(detail.at("/summary/sample").asBoolean());
            assertEquals("RUNTIME_AVAILABLE", detail.at("/runtime/state").asText());
            long id = detail.at("/summary/id").asLong();
            var upload = multipart("/api/hub/builtins/" + id + "/documents")
                .file(new MockMultipartFile("files", "requirements.txt", "text/plain", "고객 등록\n고객 목록 조회".getBytes(StandardCharsets.UTF_8)));
            var request = read(call(upload, user, null).andExpect(status().isAccepted()));
            String executionId = request.get("id").asText();
            JsonNode done = request;
            for (int attempt = 0; attempt < 50 && !done.hasNonNull("completedAt"); attempt++) {
                Thread.sleep(30);
                done = read(call(get("/api/hub/executions/" + executionId), user, null).andExpect(status().isOk()));
            }
            assertEquals("SUCCEEDED", done.get("status").asText());
            assertEquals("builtin-local", done.get("runtimeKind").asText());
            assertFalse(done.get("simulated").asBoolean());
            assertTrue(done.get("result").asText().contains("고객 등록"));
            call(get("/api/hub/executions/" + executionId), stranger, null).andExpect(status().isNotFound());
            var duplicate = multipart("/api/hub/builtins/" + id + "/documents")
                .file(new MockMultipartFile("files", "requirements.txt", "text/plain", "고객 등록\n고객 목록 조회".getBytes(StandardCharsets.UTF_8)));
            call(duplicate, user, null).andExpect(status().isConflict());
        }
        call(get("/api/hub/builtins"), null, null).andExpect(status().isUnauthorized());
    }

    @Test void exportZipContainsOnlyApprovedVisibleSkillsAndRequiresLogin() throws Exception {
        var user = account("export");
        call(get("/api/hub/skills/export.zip"), null, null).andExpect(status().isUnauthorized());
        var draft = register(user, skillMd(uniq(), "1.0.0", "doc", "test"), false, "PUBLIC");
        String draftName = draft.at("/summary/name").asText();
        byte[] zip = call(get("/api/hub/skills/export.zip"), user, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var names = new java.util.ArrayList<String>();
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = in.getNextEntry()) != null) names.add(e.getName());
        }
        assertTrue(names.contains("cen-log-analyzer/SKILL.md"));
        assertTrue(names.contains("README.md"));
        assertFalse(names.stream().anyMatch((n) -> n.startsWith(draftName + "/")), "미승인 Skill 은 포함되면 안 된다");
        assertFalse(names.stream().anyMatch((n) -> n.startsWith("error-detective/")), "샘플은 기본 제외");
        assertFalse(names.stream().anyMatch((n) -> n.contains("..")));
    }

    @Test void builtinUploadRejectsUnsupportedFilesAndUnapprovedTargets() throws Exception {
        var user = account("builtin-guard");
        var builtin = skillRepo.findByName("cen-fp-estimator").orElseThrow();
        call(multipart("/api/hub/builtins/" + builtin.id + "/documents").file(new MockMultipartFile("files", "bad.pdf", "application/pdf", new byte[]{1})), user, null).andExpect(status().isBadRequest());
        var draft = register(user, skillMd(uniq(), "1.0.0", "doc", "test"), false, "PUBLIC");
        call(multipart("/api/hub/builtins/" + draft.at("/summary/id").asLong() + "/documents").file(new MockMultipartFile("files", "input.txt", "text/plain", "test".getBytes(StandardCharsets.UTF_8))), user, null).andExpect(status().isBadRequest());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired HubSkillRepository skillRepo;
    @Autowired HubUsageEventRepository usageRepo;
    @Autowired HubExecutionRepository execRepo;

    record Acct(String name, String token) {}

    Acct account(String team) throws Exception {
        String name = "h" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount u = new UserAccount(name, encoder.encode("pw-" + name), "허브-" + name, Role.USER);
        u.setTeam(team);
        users.save(u);
        return new Acct(name, login(name, "pw-" + name));
    }

    String login(String u, String p) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    Acct admin() throws Exception { return new Acct("admin", login("admin", "test-admin-pw-1234")); }

    ResultActions call(MockHttpServletRequestBuilder b, Acct a, String body) throws Exception {
        if (a != null) b.header("Authorization", "Bearer " + a.token());
        if (body != null) b.contentType(new MediaType("application", "json", StandardCharsets.UTF_8)).content(body);
        return mvc.perform(b);
    }

    JsonNode read(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    static String uniq() { return "sk-" + UUID.randomUUID().toString().substring(0, 8); }

    static String skillMd(String name, String version, String category, String tags) {
        return "---\nname: " + name + "\ndescription: 테스트용 Skill " + name + "\n" + (version == null ? "" : "version: " + version + "\n")
                + (category == null ? "" : "category: " + category + "\n") + (tags == null ? "" : "tags: [" + tags + "]\n") + "---\n\n# " + name + "\n\n## 사용 방법\n사용법\n\n## 실행 예시\n예시 입력\n";
    }

    JsonNode register(Acct a, String md, boolean submit, String visibility) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("skillMd", md, "submit", submit, "visibility", visibility == null ? "PUBLIC" : visibility));
        return read(call(post("/api/hub/skills"), a, body).andExpect(status().isCreated()));
    }

    JsonNode registerApproved(Acct owner, Acct admin, String name, String visibility) throws Exception {
        JsonNode d = register(owner, skillMd(name, null, "dev", "java"), true, visibility);
        return read(call(post("/api/hub/skills/" + d.at("/summary/id").asLong() + "/versions/" + d.at("/current/meta/id").asLong() + "/approve"), admin, "{\"comment\":\"ok\"}").andExpect(status().isOk()));
    }

    long sid(JsonNode d) { return d.at("/summary/id").asLong(); }

    long vid(JsonNode d) { return d.at("/current/meta/id").asLong(); }

    // ───────────── 마이그레이션 / 샘플 / 런타임 ─────────────

    @Test
    void migrationsAreAppliedAndSamplesAreSeededButFlagged() throws Exception {
        Integer applied = jdbc.queryForObject("select count(*) from \"flyway_schema_history\" where \"success\" = true", Integer.class);
        assertTrue(applied != null && applied >= 2, "V1, V2 마이그레이션이 적용되어야 한다");
        Acct u = account("개발팀");
        JsonNode list = read(call(get("/api/hub/skills?size=100"), u, null).andExpect(status().isOk()));
        boolean ed = false, ra = false;
        for (JsonNode s : list.get("items")) {
            if (s.get("name").asText().equals("error-detective")) { ed = s.get("sample").asBoolean(); }
            if (s.get("name").asText().equals("requirement-analyst")) { ra = s.get("sample").asBoolean(); }
        }
        assertTrue(ed && ra, "샘플 Skill 이 sample=true 로 보여야 한다");
        // 샘플은 전체 통계의 등록 수에 포함되지 않는다
        long real = skillRepo.findAll().stream().filter(s -> !s.sample && !"ARCHIVED".equals(s.status)).count();
        assertEquals(real, read(call(get("/api/hub/analytics/overview"), u, null)).get("skills").asLong());
    }

    @Test
    void runtimeIsNotConfiguredByDefaultAndExecutionIsNotAccepted() throws Exception {
        Acct owner = account("개발팀"), user = account("개발팀"), admin = admin();
        assertEquals("RUNTIME_NOT_CONFIGURED", read(call(get("/api/hub/runtime"), user, null)).get("state").asText());
        JsonNode d = registerApproved(owner, admin, uniq(), "PUBLIC");
        JsonNode detail = read(call(get("/api/hub/skills/" + sid(d)), user, null));
        assertEquals("RUNTIME_NOT_CONFIGURED", detail.at("/runtime/state").asText());
        assertEquals("RUNTIME_NOT_CONFIGURED", detail.at("/summary/executionState").asText());
        String res = call(post("/api/hub/executions"), user, "{\"skillId\":" + sid(d) + ",\"input\":\"hello\"}").andExpect(status().isConflict()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(res.contains("RUNTIME_NOT_CONFIGURED") && res.contains("준비 중"));
        // 요청이 거부되었으므로 실행 기록/통계가 생기지 않는다
        assertEquals(0, read(call(get("/api/hub/executions"), user, null)).get("total").asInt());
    }

    // ───────────── 등록 / 검증 ─────────────

    @Test
    void registerViaJsonMultipartAndZipThenDuplicateNameIsRejected() throws Exception {
        Acct a = account("개발팀");
        String n1 = uniq(), n2 = uniq(), n3 = uniq();
        JsonNode d1 = register(a, skillMd(n1, "1.0.0", "doc", "문서,요약"), false, null);
        assertEquals("DRAFT", d1.at("/summary/status").asText());
        assertEquals("doc", d1.at("/summary/category").asText());
        assertTrue(d1.at("/summary/tags").toString().contains("문서"));
        assertEquals(skillMd(n1, "1.0.0", "doc", "문서,요약"), d1.at("/current/skillMd").asText(), "원본 SKILL.md 가 그대로 보존되어야 한다");
        assertEquals("사용법", d1.at("/current/usage").asText());
        assertEquals("예시 입력", d1.at("/current/example").asText());

        mvc.perform(multipart("/api/hub/skills/upload").file(new MockMultipartFile("file", "SKILL.md", "text/markdown", skillMd(n2, null, null, null).getBytes(StandardCharsets.UTF_8)))
                .param("submit", "true").header("Authorization", "Bearer " + a.token())).andExpect(status().isCreated());

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            z.putNextEntry(new ZipEntry("SKILL.md")); z.write(skillMd(n3, null, "data", null).getBytes(StandardCharsets.UTF_8)); z.closeEntry();
            z.putNextEntry(new ZipEntry("ref/guide.md")); z.write("# 참고".getBytes(StandardCharsets.UTF_8)); z.closeEntry();
        }
        JsonNode dz = read(mvc.perform(multipart("/api/hub/skills/upload").file(new MockMultipartFile("file", "pack.zip", "application/zip", bo.toByteArray()))
                .header("Authorization", "Bearer " + a.token())).andExpect(status().isCreated()));
        assertEquals("ZIP", dz.at("/current/meta/packageKind").asText());
        assertEquals("ref/guide.md", dz.at("/current/files/1/path").asText());

        call(post("/api/hub/skills"), a, json.writeValueAsString(java.util.Map.of("skillMd", skillMd(n1, null, null, null)))).andExpect(status().isConflict());
    }

    @Test
    void invalidAndMaliciousUploadsAreRejectedAndAudited() throws Exception {
        Acct a = account("개발팀"), admin = admin();
        call(post("/api/hub/skills"), a, "{\"skillMd\":\"# frontmatter 없음\"}").andExpect(status().isBadRequest());
        call(post("/api/hub/skills"), a, "{\"skillMd\":\"---\\ndescription: d\\n---\\n\"}").andExpect(status().isBadRequest());
        call(post("/api/hub/skills"), a, json.writeValueAsString(java.util.Map.of("skillMd", skillMd(uniq(), null, "no-such-category", null)))).andExpect(status().isBadRequest());

        for (String evil : new String[]{"../evil.md", "run.sh", "nested.zip"}) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(bo)) {
                z.putNextEntry(new ZipEntry("SKILL.md")); z.write(skillMd(uniq(), null, null, null).getBytes(StandardCharsets.UTF_8)); z.closeEntry();
                z.putNextEntry(new ZipEntry(evil)); z.write("x".getBytes()); z.closeEntry();
            }
            mvc.perform(multipart("/api/hub/skills/upload").file(new MockMultipartFile("file", "p.zip", "application/zip", bo.toByteArray()))
                    .header("Authorization", "Bearer " + a.token())).andExpect(status().isBadRequest());
        }
        mvc.perform(multipart("/api/hub/skills/upload").file(new MockMultipartFile("file", "tool.exe", "application/octet-stream", new byte[]{'M', 'Z', 1, 2}))
                .header("Authorization", "Bearer " + a.token())).andExpect(status().isBadRequest());

        JsonNode logs = read(call(get("/api/audit-logs?limit=200"), admin, null));
        int rejected = 0;
        for (JsonNode l : logs) if (l.get("action").asText().equals("HUB_UPLOAD_REJECTED") && l.get("actor").asText().equals(a.name())) rejected++;
        assertTrue(rejected >= 5, "거부 사유가 감사 로그에 남아야 한다: " + rejected);
    }

    // ───────────── 승인 / 가시성 / 검색 ─────────────

    @Test
    void newSkillIsPrivateUntilAdminApprovesAndSelfApprovalIsBlocked() throws Exception {
        Acct owner = account("개발팀"), other = account("개발팀"), admin = admin();
        String name = uniq();
        JsonNode d = register(owner, skillMd(name, null, "dev", "java"), true, null);
        assertEquals("PENDING_REVIEW", d.at("/summary/status").asText());
        call(get("/api/hub/skills/" + sid(d)), other, null).andExpect(status().isNotFound()); // 승인 전에는 존재 자체가 숨겨진다
        assertEquals(0, read(call(get("/api/hub/skills?q=" + name), other, null)).get("total").asInt());
        assertEquals(1, read(call(get("/api/hub/skills?q=" + name), owner, null)).get("total").asInt());

        // 검토 대기열(관리자 전용)에 위험 플래그와 함께 노출
        call(get("/api/hub/review-queue"), owner, null).andExpect(status().isForbidden());
        boolean queued = false;
        for (JsonNode q : read(call(get("/api/hub/review-queue"), admin, null))) if (q.get("skillId").asLong() == sid(d)) queued = true;
        assertTrue(queued);

        // 권한: 일반 사용자는 승인 불가, 반려는 사유 필수
        String base = "/api/hub/skills/" + sid(d) + "/versions/" + vid(d);
        call(post(base + "/approve"), owner, "{}").andExpect(status().isForbidden());
        call(post(base + "/reject"), admin, "{}").andExpect(status().isBadRequest());
        JsonNode rejected = read(call(post(base + "/reject"), admin, "{\"comment\":\"설명 보강 필요\"}").andExpect(status().isOk()));
        assertEquals("REJECTED", rejected.at("/summary/status").asText());
        call(post(base + "/approve"), admin, "{}").andExpect(status().isConflict()); // 반려된 버전은 재제출 후 승인

        call(post(base + "/submit"), other, null).andExpect(status().isNotFound()); // 보이지 않는 Skill 은 존재 자체를 숨긴다
        call(post(base + "/submit"), owner, null).andExpect(status().isOk());
        JsonNode ok = read(call(post(base + "/approve"), admin, "{\"comment\":\"승인\"}").andExpect(status().isOk()));
        assertEquals("APPROVED", ok.at("/summary/status").asText());
        call(get("/api/hub/skills/" + sid(d)), other, null).andExpect(status().isOk());

        // 관리자가 등록한 Skill 은 본인이 승인할 수 없다
        JsonNode mine = register(admin, skillMd(uniq(), null, null, null), true, null);
        call(post("/api/hub/skills/" + sid(mine) + "/versions/" + vid(mine) + "/approve"), admin, "{}").andExpect(status().isForbidden());

        JsonNode hist = read(call(get("/api/hub/skills/" + sid(d) + "/history"), owner, null));
        assertTrue(hist.toString().contains("APPROVED") && hist.toString().contains("REJECTED") && hist.toString().contains("SUBMITTED"));
        call(get("/api/hub/skills/" + sid(d) + "/history"), other, null).andExpect(status().isForbidden());
    }

    @Test
    void teamVisibilityLimitsAccessToTheSameTeam() throws Exception {
        Acct owner = account("플랫폼팀"), sameTeam = account("플랫폼팀"), otherTeam = account("재무팀"), admin = admin();
        JsonNode d = registerApproved(owner, admin, uniq(), "TEAM");
        call(get("/api/hub/skills/" + sid(d)), sameTeam, null).andExpect(status().isOk());
        call(get("/api/hub/skills/" + sid(d)), otherTeam, null).andExpect(status().isNotFound());
        call(get("/api/hub/skills/" + sid(d) + "/download"), otherTeam, null).andExpect(status().isNotFound());
        assertEquals(0, read(call(get("/api/hub/skills?q=" + d.at("/summary/name").asText()), otherTeam, null)).get("total").asInt());
        // 관리자는 모두 볼 수 있다
        call(get("/api/hub/skills/" + sid(d)), admin, null).andExpect(status().isOk());
    }

    @Test
    void searchFilterSortAndPagination() throws Exception {
        Acct c1 = account("개발팀"), c2 = account("개발팀"), viewer = account("개발팀"), admin = admin();
        String tag = "tg" + UUID.randomUUID().toString().substring(0, 6);
        String a = uniq(), b = uniq(), c = uniq();
        for (var e : new Object[][]{{c1, a, "dev"}, {c1, b, "doc"}, {c2, c, "dev"}}) {
            JsonNode d = register((Acct) e[0], skillMd((String) e[1], null, (String) e[2], tag), true, null);
            call(post("/api/hub/skills/" + sid(d) + "/versions/" + vid(d) + "/approve"), admin, "{}").andExpect(status().isOk());
        }
        assertEquals(3, read(call(get("/api/hub/skills?tag=" + tag), viewer, null)).get("total").asInt());
        assertEquals(2, read(call(get("/api/hub/skills?tag=" + tag + "&category=dev"), viewer, null)).get("total").asInt());
        assertEquals(2, read(call(get("/api/hub/skills?tag=" + tag + "&owner=" + c1.name()), viewer, null)).get("total").asInt());
        assertEquals(1, read(call(get("/api/hub/skills?q=" + b.toUpperCase()), viewer, null)).get("total").asInt(), "이름 검색은 대소문자를 구분하지 않는다");
        JsonNode page = read(call(get("/api/hub/skills?tag=" + tag + "&size=2&page=1&sort=recent"), viewer, null));
        assertEquals(3, page.get("total").asInt());
        assertEquals(1, page.get("items").size());
        assertEquals(c, read(call(get("/api/hub/skills?tag=" + tag + "&sort=recent&size=1"), viewer, null)).at("/items/0/name").asText(), "최신순이면 마지막에 승인된 것이 먼저");
        call(get("/api/hub/skills?q=%25"), viewer, null).andExpect(status().isOk()); // LIKE 와일드카드 입력도 안전
        assertEquals(8, read(call(get("/api/hub/categories"), viewer, null)).size());
    }

    // ───────────── 버전 / 롤백 / 다운로드 ─────────────

    @Test
    void versionsKeepOriginalsApproveNewVersionRollbackAndDownload() throws Exception {
        Acct owner = account("개발팀"), user = account("개발팀"), admin = admin();
        String name = uniq();
        JsonNode v1 = register(owner, skillMd(name, "1.0.0", "dev", "java"), true, null);
        long id = sid(v1);
        long vid1 = vid(v1);
        call(post("/api/hub/skills/" + id + "/versions/" + vid1 + "/approve"), admin, "{}").andExpect(status().isOk());

        // 새 버전 제출 → 승인 전에는 기존 공개 버전(1.0.0)이 유지된다
        String md2 = skillMd(name, "1.1.0", "dev", "java").replace("사용법", "개선된 사용법");
        JsonNode r2 = read(call(post("/api/hub/skills/" + id + "/versions"), owner, json.writeValueAsString(java.util.Map.of("skillMd", md2, "submit", true))).andExpect(status().isCreated()));
        long vid2 = 0;
        for (JsonNode v : r2.get("versions")) if (v.get("version").asText().equals("1.1.0")) vid2 = v.get("id").asLong();
        assertTrue(vid2 > 0);
        JsonNode asUser = read(call(get("/api/hub/skills/" + id), user, null));
        assertEquals("1.0.0", asUser.at("/current/meta/version").asText());
        assertEquals(1, asUser.get("versions").size(), "일반 사용자에게는 승인된 버전만 보인다");
        call(get("/api/hub/skills/" + id + "?versionId=" + vid2), user, null).andExpect(status().isNotFound());
        call(post("/api/hub/skills/" + id + "/versions"), owner, json.writeValueAsString(java.util.Map.of("skillMd", md2))).andExpect(status().isConflict()); // 같은 버전 라벨
        call(post("/api/hub/skills/" + id + "/versions"), owner, json.writeValueAsString(java.util.Map.of("skillMd", skillMd(uniq(), "9.0.0", null, null)))).andExpect(status().isBadRequest()); // name 불일치
        call(post("/api/hub/skills/" + id + "/versions"), user, json.writeValueAsString(java.util.Map.of("skillMd", skillMd(name, "2.0.0", null, null)))).andExpect(status().isForbidden());

        call(post("/api/hub/skills/" + id + "/versions/" + vid2 + "/approve"), admin, "{}").andExpect(status().isOk());
        JsonNode now = read(call(get("/api/hub/skills/" + id), user, null));
        assertEquals("1.1.0", now.at("/current/meta/version").asText());
        assertEquals(md2, now.at("/current/skillMd").asText());
        // 이전 버전 원본 보존 + 롤백
        assertEquals(skillMd(name, "1.0.0", "dev", "java"), read(call(get("/api/hub/skills/" + id + "?versionId=" + vid1), user, null)).at("/current/skillMd").asText());
        call(post("/api/hub/skills/" + id + "/rollback"), owner, "{\"versionId\":" + vid1 + "}").andExpect(status().isForbidden());
        call(post("/api/hub/skills/" + id + "/rollback"), admin, "{\"versionId\":" + vid1 + ",\"comment\":\"회귀 발견\"}").andExpect(status().isOk());
        assertEquals("1.0.0", read(call(get("/api/hub/skills/" + id), user, null)).at("/current/meta/version").asText());
        call(post("/api/hub/skills/" + id + "/rollback"), admin, "{\"versionId\":" + vid1 + "}").andExpect(status().isConflict());

        // 다운로드: MD 는 attachment + nosniff, 승인 안 된 버전은 제작자만
        mvc.perform(get("/api/hub/skills/" + id + "/download").header("Authorization", "Bearer " + user.token())).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
        String body = mvc.perform(get("/api/hub/skills/" + id + "/download?versionId=" + vid1).header("Authorization", "Bearer " + user.token())).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(skillMd(name, "1.0.0", "dev", "java"), body);
        JsonNode draft = read(call(post("/api/hub/skills/" + id + "/versions"), owner, json.writeValueAsString(java.util.Map.of("skillMd", skillMd(name, "2.0.0", null, null), "submit", false))));
        long vidDraft = 0;
        for (JsonNode v : draft.get("versions")) if (v.get("version").asText().equals("2.0.0")) vidDraft = v.get("id").asLong();
        call(get("/api/hub/skills/" + id + "/download?versionId=" + vidDraft), user, null).andExpect(status().isNotFound());
        call(get("/api/hub/skills/" + id + "/download?versionId=" + vidDraft), owner, null).andExpect(status().isOk());
    }

    // ───────────── Agent ─────────────

    @Test
    void agentLinksOnlyApprovedSkillsAndReapprovalIsRequiredAfterChanges() throws Exception {
        Acct owner = account("개발팀"), user = account("개발팀"), admin = admin();
        JsonNode approved = registerApproved(owner, admin, uniq(), "PUBLIC");
        JsonNode pending = register(owner, skillMd(uniq(), null, null, null), true, null);

        String req = json.writeValueAsString(java.util.Map.of("name", "개발 지원 " + uniq(), "description", "오류 분석과 리뷰", "duties", "코드 리뷰",
                "skillIds", java.util.List.of(sid(approved)), "config", "{\"maxSteps\":5,\"outputFormat\":\"markdown\"}", "submit", true));
        JsonNode agent = read(call(post("/api/hub/agents"), owner, req).andExpect(status().isCreated()));
        long aid = agent.at("/summary/id").asLong();
        assertEquals("PENDING_REVIEW", agent.at("/summary/status").asText());
        assertEquals(1, agent.at("/summary/skillCount").asInt());
        call(get("/api/hub/agents/" + aid), user, null).andExpect(status().isNotFound());

        // 승인되지 않은 Skill / 잘못된 설정 / 중복 / 개수 초과는 거부
        call(post("/api/hub/agents/" + aid + "/skills/" + sid(pending)), owner, null).andExpect(status().isBadRequest());
        call(post("/api/hub/agents/" + aid + "/skills/" + sid(approved)), owner, null).andExpect(status().isConflict());
        call(put("/api/hub/agents/" + aid), owner, "{\"config\":\"{\\\"rm\\\":1}\"}").andExpect(status().isBadRequest());
        call(put("/api/hub/agents/" + aid), owner, "{\"config\":\"{\\\"maxSteps\\\":99}\"}").andExpect(status().isBadRequest());
        call(post("/api/hub/agents"), owner, json.writeValueAsString(java.util.Map.of("name", "x", "description", "d"))).andExpect(status().isBadRequest());

        call(post("/api/hub/agents/" + aid + "/approve"), owner, "{}").andExpect(status().isForbidden());
        read(call(post("/api/hub/agents/" + aid + "/approve"), admin, "{\"comment\":\"ok\"}").andExpect(status().isOk()));
        call(get("/api/hub/agents/" + aid), user, null).andExpect(status().isOk());
        assertEquals(1, read(call(get("/api/hub/agents?q=" + agent.at("/summary/name").asText().split(" ")[2]), user, null)).get("total").asInt());

        // 승인 후 구성 변경 → 버전 증가 + 재승인 전까지 검토 대기(비공개)
        JsonNode second = registerApproved(owner, admin, uniq(), "PUBLIC");
        JsonNode changed = read(call(post("/api/hub/agents/" + aid + "/skills/" + sid(second)), owner, null).andExpect(status().isOk()));
        assertEquals(2, changed.at("/summary/version").asInt());
        assertEquals("PENDING_REVIEW", changed.at("/summary/status").asText());
        assertEquals(2, changed.get("skills").size());
        assertEquals(2, changed.get("versions").size());
        call(get("/api/hub/agents/" + aid), user, null).andExpect(status().isNotFound());
        JsonNode unlinked = read(call(delete("/api/hub/agents/" + aid + "/skills/" + sid(second)), owner, null).andExpect(status().isOk()));
        assertEquals(1, unlinked.get("skills").size());
        call(delete("/api/hub/agents/" + aid + "/skills/" + sid(second)), owner, null).andExpect(status().isNotFound());
        call(put("/api/hub/agents/" + aid), user, "{\"description\":\"탈취\"}").andExpect(status().isNotFound());
    }

    // ───────────── 평가 / 권한 ─────────────

    @Test
    void reviewsAreValidatedAndOwnerCannotRateOwnSkill() throws Exception {
        Acct owner = account("개발팀"), r1 = account("개발팀"), r2 = account("개발팀"), admin = admin();
        JsonNode d = registerApproved(owner, admin, uniq(), "PUBLIC");
        String url = "/api/hub/skills/" + sid(d) + "/reviews";
        call(post(url), owner, "{\"rating\":5}").andExpect(status().isForbidden());
        call(post(url), r1, "{\"rating\":6}").andExpect(status().isBadRequest());
        call(post(url), r1, "{\"rating\":0}").andExpect(status().isBadRequest());
        call(post(url), r1, "{\"rating\":5,\"comment\":\"좋아요\"}").andExpect(status().isOk());
        call(post(url), r1, "{\"rating\":4}").andExpect(status().isOk()); // 한 사람당 하나(수정)
        call(post(url), r2, "{\"rating\":2,\"comment\":\"아쉬워요\"}").andExpect(status().isOk());
        JsonNode detail = read(call(get("/api/hub/skills/" + sid(d)), r1, null));
        assertEquals(2, detail.at("/summary/ratingCount").asLong());
        assertEquals(3.0, detail.at("/summary/ratingAvg").asDouble(), 0.01);
        assertEquals(2, read(call(get(url), r1, null)).size());
    }

    @Test
    void authorizationMatrix() throws Exception {
        Acct owner = account("개발팀"), other = account("개발팀"), admin = admin();
        mvc.perform(get("/api/hub/skills")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/hub/skills").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/hub/analytics/overview")).andExpect(status().isUnauthorized());
        JsonNode d = registerApproved(owner, admin, uniq(), "PUBLIC");
        call(put("/api/hub/skills/" + sid(d)), other, "{\"category\":\"hr\"}").andExpect(status().isForbidden());
        call(delete("/api/hub/skills/" + sid(d)), other, null).andExpect(status().isForbidden());
        call(post("/api/hub/skills/" + sid(d) + "/versions/" + vid(d) + "/approve"), other, "{}").andExpect(status().isForbidden());
        call(get("/api/hub/review-queue"), other, null).andExpect(status().isForbidden());
        call(get("/api/hub/analytics/creators/" + owner.name()), other, null).andExpect(status().isForbidden());
        call(get("/api/hub/analytics/creators/" + owner.name()), owner, null).andExpect(status().isOk());
        call(get("/api/hub/analytics/creators/" + owner.name()), admin, null).andExpect(status().isOk());
        call(put("/api/hub/skills/" + sid(d)), owner, "{\"category\":\"hr\",\"tags\":[\"a\",\"b\"]}").andExpect(status().isOk());
        call(delete("/api/hub/skills/" + sid(d)), owner, null).andExpect(status().isNoContent());
        call(get("/api/hub/skills/" + sid(d)), other, null).andExpect(status().isNotFound()); // 보관된 Skill 은 숨겨진다
        call(get("/api/hub/executions/" + UUID.randomUUID()), other, null).andExpect(status().isNotFound());
    }

    // ───────────── KPI 집계 ─────────────

    @Test
    void kpiCountsOnlyRealEventsAndExcludesSimulatedSampleAndCreatorSelfUse() throws Exception {
        Acct creator = account("개발팀"), u1 = account("개발팀"), u2 = account("개발팀"), admin = admin();
        JsonNode d = registerApproved(creator, admin, uniq(), "PUBLIC");
        long skill = sid(d), version = vid(d);
        long before = read(call(get("/api/hub/analytics/overview"), u1, null)).get("executionsThisMonth").asLong();

        // 실제(집계 대상) 이벤트: u1 2회, u2 1회 요청 / 성공 2, 실패 1
        ev(skill, version, u1.name(), "EXECUTION_REQUESTED", true, 0); ev(skill, version, u1.name(), "EXECUTION_REQUESTED", true, 0); ev(skill, version, u2.name(), "EXECUTION_REQUESTED", true, 0);
        ev(skill, version, u1.name(), "EXECUTION_SUCCEEDED", true, 0); ev(skill, version, u1.name(), "EXECUTION_SUCCEEDED", true, 0); ev(skill, version, u2.name(), "EXECUTION_FAILED", true, 0);
        // 집계 제외: 제작자 본인 / 시뮬레이션 → counted=false, 그리고 오래된 요청(40일 전)은 30일 지표에서 제외
        ev(skill, version, creator.name(), "EXECUTION_REQUESTED", false, 0); ev(skill, version, u1.name(), "EXECUTION_REQUESTED", false, 0);
        ev(skill, version, u2.name(), "EXECUTION_REQUESTED", true, 40);

        JsonNode st = read(call(get("/api/hub/analytics/skills/" + skill), u1, null).andExpect(status().isOk()));
        assertEquals(4, st.at("/counts/requests").asInt());
        assertEquals(2, st.at("/counts/succeeded").asInt());
        assertEquals(1, st.at("/counts/failed").asInt());
        assertEquals(2, st.at("/counts/uniqueUsers").asInt());
        assertEquals(2, st.at("/counts/repeatUsers").asInt(), "u1(2회), u2(2회: 최근1+오래된1) 모두 재사용자");
        assertEquals(3, st.at("/counts/last7d").asInt());
        assertEquals(3, st.at("/counts/last30d").asInt());
        assertEquals(4, st.at("/byVersion").get("1.0.0").asInt());
        assertEquals(14, st.get("daily").size());

        long after = read(call(get("/api/hub/analytics/overview"), u1, null)).get("executionsThisMonth").asLong();
        assertEquals(before + 3, after, "이번 달 집계에는 counted 이벤트만 포함(오래된 것/제외 대상 제외)");

        JsonNode cs = read(call(get("/api/hub/analytics/creators/me"), creator, null).andExpect(status().isOk()));
        assertEquals(1, cs.get("skillsApproved").asInt());
        assertEquals(4, cs.at("/totals/requests").asInt());
        assertEquals(6, cs.get("monthly").size());
        assertTrue(cs.get("note").asText().contains("점수·보상은 계산하지 않고"));

        // 샘플 Skill 이벤트는 counted=false 로만 기록된다 (HubSkillService.recordEvent 규칙)
        HubSkill sample = skillRepo.findByName("error-detective").orElseThrow();
        assertTrue(sample.sample);
        call(get("/api/hub/skills/" + sample.id + "/download"), u1, null).andExpect(status().isOk());
        assertTrue(usageRepo.findAll().stream().filter(e -> e.skillId.equals(sample.id) && e.type.equals("DOWNLOAD")).noneMatch(e -> e.counted));
        assertEquals(0, read(call(get("/api/hub/analytics/skills/" + sample.id), u1, null)).at("/counts/requests").asInt());
    }

    private void ev(long skill, long version, String actor, String type, boolean counted, int daysAgo) {
        HubUsageEvent e = new HubUsageEvent();
        e.skillId = skill; e.versionId = version; e.actor = actor; e.type = type; e.executionId = UUID.randomUUID().toString(); e.counted = counted;
        e.occurredAt = Instant.now().minusSeconds(daysAgo * 86400L);
        usageRepo.save(e);
    }
}
