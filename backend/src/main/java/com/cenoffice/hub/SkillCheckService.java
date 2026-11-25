package com.cenoffice.hub;

import com.cenoffice.common.api.ApiException;
import com.cenoffice.common.llm.LlmProvider;
import com.cenoffice.hub.domain.HubSkill;
import com.cenoffice.hub.domain.HubSkillRepository;
import com.cenoffice.hub.domain.HubSkillVersion;
import com.cenoffice.hub.domain.HubSkillVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 스킬 검증: 승인 요청된 버전을 '스킬 검증(cen-skill-check)' 기준으로 점검한다.
 * 1) 규칙 점검(항상): 앞머리 항목, 필수 내용, 가리키는 파일, 위험 표현, 비슷한 공개 스킬.
 * 2) AI 검토(AI 가 연결되어 있으면): cen-skill-check 패키지의 지침으로 쓸모·명확성·안전을 판단.
 * 결과(PASS / FIX / REJECT 와 보고서)는 버전에 저장하고 관리자가 승인 화면에서 본다. 승인 여부는 관리자가 정한다.
 */
@Service
public class SkillCheckService {
    private static final Logger log = LoggerFactory.getLogger(SkillCheckService.class);
    public static final String CHECKER = "cen-skill-check";
    static final List<String> ORDER = List.of("PASS", "FIX", "REJECT");
    public record Issue(String area, String level, String problem, String fix) {}
    public record Result(String verdict, String report) {}

    private final HubSkillVersionRepository versions;
    private final HubSkillRepository skills;
    private final SkillService skillService;
    private final LlmProvider llm;
    private final ObjectMapper json;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "skill-check"); t.setDaemon(true); return t; });

    public SkillCheckService(HubSkillVersionRepository versions, HubSkillRepository skills, SkillService skillService, LlmProvider llm, ObjectMapper json) {
        this.versions = versions; this.skills = skills; this.skillService = skillService; this.llm = llm; this.json = json;
    }
    @PreDestroy void stop() { exec.shutdownNow(); }

    /** 승인 요청이 저장(커밋)된 뒤 백그라운드에서 점검한다. AI 검토가 수 초 걸려도 등록은 바로 끝난다 */
    @org.springframework.transaction.event.TransactionalEventListener(fallbackExecution = true)
    public void onSubmitted(SkillService.VersionSubmitted e) {
        exec.submit(() -> { try { checkNow(e.versionId()); } catch (Exception x) { log.warn("스킬 검증 실패 v{}: {}", e.versionId(), x.toString()); } });
    }

    /** 지금 점검하고 결과를 저장한다(관리자의 '다시 검증') */
    public Result checkNow(Long versionId) {
        HubSkillVersion v = versions.findById(versionId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "버전을 찾을 수 없습니다"));
        HubSkill s = skills.findById(v.skillId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "스킬을 찾을 수 없습니다"));
        Result r = check(s, v);
        v.checkVerdict = r.verdict(); v.checkReport = r.report(); v.checkedAt = Instant.now();
        versions.save(v);
        return r;
    }

    Result check(HubSkill s, HubSkillVersion v) {
        List<Issue> rules = ruleIssues(s, v);
        String verdict = verdictOf(rules);
        StringBuilder rep = new StringBuilder("## 자동 점검 (규칙)\n\n");
        if (rules.isEmpty()) rep.append("- 형식·필수 내용·첨부 파일·위험 표현·중복에서 문제를 찾지 못했습니다.\n");
        else { rep.append("| 영역 | 심각도 | 문제 | 고칠 방법 |\n|---|---|---|---|\n"); rules.forEach(i -> rep.append(row(i))); }

        if (llm.isReal()) {
            try {
                var ai = aiReview(v);
                verdict = worst(verdict, ai.verdict());
                rep.append("\n## AI 검토\n\n").append(ai.report().strip()).append('\n');
            } catch (Exception e) {
                rep.append("\n## AI 검토\n\n- AI 검토를 하지 못했습니다(").append(e.getMessage() == null ? "오류" : e.getMessage()).append("). 규칙 점검 결과만 반영했습니다.\n");
            }
        } else rep.append("\n## AI 검토\n\n- AI 가 연결되어 있지 않아 규칙 점검만 했습니다.\n");
        String head = "**판정: " + label(verdict) + "** — " + switch (verdict) {
            case "PASS" -> "공개해도 될 수준입니다. 내용을 확인하고 승인해 주세요.";
            case "FIX" -> "고칠 점이 있습니다. 제작자에게 보완을 요청하거나 확인 후 승인해 주세요.";
            default -> "안전 문제가 있거나 쓸모가 부족합니다. 반려를 권합니다.";
        } + "\n\n";
        String report = head + rep;
        return new Result(verdict, report.length() > 19000 ? report.substring(0, 19000) : report);
    }

    // ───────────────────────── 규칙 점검 ─────────────────────────

    private static final Pattern REF = Pattern.compile("`((?:references|templates|examples|assets|scripts)/[^`\\s]+)`");
    private static final Map<String, String> SECTIONS = new LinkedHashMap<>();
    static {
        SECTIONS.put("언제 쓰는지·사용 방법", "언제|사용\\s*방법|사용법|usage");
        SECTIONS.put("입력", "입력|input");
        SECTIONS.put("진행 방법", "진행|단계|수행\\s*지침|방법|steps");
        SECTIONS.put("결과 형식", "결과|출력|output");
        SECTIONS.put("예시", "예시|example");
        SECTIONS.put("주의할 점", "주의|제한|한계|caution");
    }

    List<Issue> ruleIssues(HubSkill s, HubSkillVersion v) {
        List<Issue> out = new ArrayList<>();
        String md = v.skillMd == null ? "" : v.skillMd;
        String desc = s.description == null ? "" : s.description.strip();
        if (desc.length() < 20) out.add(new Issue("명확성", "중간", "설명(description)이 너무 짧음(" + desc.length() + "자)", "무엇을 하고 언제 쓰는지 한두 문장(20자 이상)으로"));
        if (s.title == null || s.title.isBlank()) out.add(new Issue("명확성", "낮음", "화면 이름(title)이 없음", "업무 이름을 title 에 적기(예: 주간 보고서 작성)"));
        String body = md.replaceFirst("(?s)^---.*?\\n---", "");
        for (var e : SECTIONS.entrySet())
            if (!Pattern.compile("(?im)^#{1,4}\\s*.*(?:" + e.getValue() + ")").matcher(body).find())
                out.add(new Issue("완결성", e.getKey().equals("주의할 점") ? "낮음" : "중간", "'" + e.getKey() + "' 내용이 없음", "## " + e.getKey() + " 제목으로 추가"));
        if (body.strip().length() < 200) out.add(new Issue("쓸모", "중간", "지침 본문이 너무 짧음(" + body.strip().length() + "자)", "진행 단계와 결과 형식을 구체적으로"));
        if (md.length() > 20000) out.add(new Issue("명확성", "낮음", "SKILL.md 가 김(" + md.length() + "자)", "자세한 내용은 references/ 파일로 나누기"));
        Set<String> files = new HashSet<>();
        if ("ZIP".equals(v.packageKind)) files.addAll(SkillPackageReader.textFiles(v.packageData).keySet());
        if (v.fileList != null) for (var m = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"").matcher(v.fileList); m.find(); ) files.add(m.group(1));
        for (var m = REF.matcher(md); m.find(); ) {
            String f = m.group(1);
            if (!f.endsWith("/") && !files.contains(f)) out.add(new Issue("완결성", "중간", "SKILL.md 가 가리키는 '" + f + "' 파일이 없음", "파일을 함께 올리거나 언급을 지우기"));
        }
        if (v.riskFlags != null && !v.riskFlags.isBlank())
            for (String f : v.riskFlags.split(" \\| ")) out.add(new Issue("안전", f.contains("인젝션") ? "높음" : "중간", "위험 표현: " + f, "필요 없는 표현이면 지우고, 꼭 필요하면 이유를 적기"));
        similar(s).ifPresent(o -> out.add(new Issue("중복", "중간", "이미 공개된 '" + o + "' 스킬과 비슷함", "기존 스킬을 개선하거나 차이를 설명에 적기")));
        return out;
    }

    private Optional<String> similar(HubSkill s) {
        Set<String> mine = words((s.title == null ? "" : s.title) + " " + s.description);
        if (mine.size() < 3) return Optional.empty();
        for (HubSkill o : skills.findAll()) {
            if (o.id.equals(s.id) || !"APPROVED".equals(o.status) || o.publishedVersionId == null) continue;
            if (s.title != null && s.title.equals(o.title)) return Optional.of(o.title);
            Set<String> other = words((o.title == null ? "" : o.title) + " " + o.description);
            Set<String> inter = new HashSet<>(mine); inter.retainAll(other);
            Set<String> uni = new HashSet<>(mine); uni.addAll(other);
            if (!uni.isEmpty() && (double) inter.size() / uni.size() >= 0.6) return Optional.of(o.title != null ? o.title : o.name);
        }
        return Optional.empty();
    }
    private static Set<String> words(String t) {
        Set<String> w = new HashSet<>();
        for (String x : (t == null ? "" : t).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (x.length() >= 2) w.add(x);
        return w;
    }

    static String verdictOf(List<Issue> issues) {
        if (issues.stream().anyMatch(i -> i.area().equals("안전") && i.level().equals("높음"))) return "REJECT";
        if (issues.stream().anyMatch(i -> !i.level().equals("낮음"))) return "FIX";
        return "PASS";
    }
    static String worst(String a, String b) { return ORDER.indexOf(a) >= ORDER.indexOf(b) ? a : b; }
    static String label(String v) { return switch (v) { case "PASS" -> "통과"; case "FIX" -> "보완 필요"; default -> "반려 권고"; }; }
    private static String row(Issue i) { return "| " + cell(i.area()) + " | " + cell(i.level()) + " | " + cell(i.problem()) + " | " + cell(i.fix()) + " |\n"; }
    private static String cell(String s) { return s == null ? "" : s.replace('|', '／').replace('\n', ' '); }

    // ───────────────────────── AI 검토 ─────────────────────────

    static final String OUTPUT = """
        아래 JSON 하나로만 답하세요(다른 글자 금지):
        {"verdict":"PASS|FIX|REJECT","summary":"판정 이유 한 줄","issues":[{"area":"쓸모|명확성|완결성|안전|중복","level":"높음|중간|낮음","problem":"한 줄","fix":"한 줄"}],"good":["잘된 점"]}""";

    private Result aiReview(HubSkillVersion v) throws Exception {
        String rules = checkerInstructions();
        StringBuilder target = new StringBuilder("<점검할_스킬_자료>\n").append(v.skillMd);
        if ("ZIP".equals(v.packageKind)) {
            int used = 0;
            for (var f : SkillPackageReader.textFiles(v.packageData).entrySet()) {
                if (used > 30_000) { target.append("\n(첨부 파일 일부 생략)"); break; }
                target.append("\n\n--- 첨부 파일: ").append(f.getKey()).append(" ---\n").append(f.getValue());
                used += f.getValue().length();
            }
        }
        target.append("\n</점검할_스킬_자료>");
        String raw = llm.generateJson(rules + "\n\n" + OUTPUT, target.toString());
        int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalStateException("AI 답을 이해하지 못함");
        JsonNode n = json.readTree(raw.substring(a, b + 1));
        String verdict = n.path("verdict").asText("FIX").toUpperCase(Locale.ROOT);
        if (!ORDER.contains(verdict)) verdict = "FIX";
        StringBuilder rep = new StringBuilder("- ").append(cell(n.path("summary").asText("")).strip()).append("\n\n");
        List<Issue> issues = new ArrayList<>();
        for (JsonNode i : n.path("issues")) {
            String level = i.path("level").asText("중간");
            if (!Set.of("높음", "중간", "낮음").contains(level)) level = "중간";
            issues.add(new Issue(i.path("area").asText("기타"), level, i.path("problem").asText(""), i.path("fix").asText("")));
            if (issues.size() >= 15) break;
        }
        if (issues.isEmpty()) rep.append("- 발견된 문제 없음\n");
        else { rep.append("| 영역 | 심각도 | 문제 | 고칠 방법 |\n|---|---|---|---|\n"); issues.forEach(i -> rep.append(row(i))); }
        List<String> good = new ArrayList<>();
        n.path("good").forEach(g -> { if (g.isTextual() && good.size() < 2) good.add(cell(g.asText())); });
        if (!good.isEmpty()) rep.append("\n잘된 점: ").append(String.join(" · ", good)).append('\n');
        return new Result(verdict, rep.toString());
    }

    /** 검증 기준은 cen-skill-check 스킬 패키지 자체(SKILL.md + references·templates·examples)를 쓴다. 기준을 고치려면 그 파일을 고친다 */
    static String checkerInstructions() throws java.io.IOException {
        byte[] zip = OfficialSkillSeeder.bundle(CHECKER);
        StringBuilder sb = new StringBuilder();
        try (var zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (var e = zin.getNextEntry(); e != null; e = zin.getNextEntry())
                if (e.getName().endsWith("/SKILL.md")) sb.append(new String(zin.readAllBytes(), StandardCharsets.UTF_8));
        }
        SkillPackageReader.textFiles(zip).forEach((k, t) -> sb.append("\n\n--- ").append(k).append(" ---\n").append(t));
        return sb.toString();
    }
}
