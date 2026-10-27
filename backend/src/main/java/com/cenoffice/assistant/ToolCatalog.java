package com.cenoffice.assistant;

import com.cenoffice.hub.BuiltinSkillSeeder;
import com.cenoffice.hub.HubDtos.SkillSummary;
import com.cenoffice.hub.SkillService;
import com.cenoffice.hub.runtime.RuntimeRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 입력창이 쓸 수 있는 AI 허브 도구 목록. "승인된 도구 중 이 사용자가 볼 수 있고 실제로 실행할 수 있는 것"만 담는다.
 * 어떤 도구를 쓸지는 AI(또는 키워드 점수)가 고르지만, 실행은 기존 허브 실행 API 가 권한·승인 상태를 다시 확인한 뒤에만 한다.
 */
@Component
public class ToolCatalog {
    /** 도구 하나. engine: rule(기본 제공 도구, 서버 안에서 처리) | ai(AI 모델로 처리, 입력이 AI 서비스로 전송됨) */
    public record Tool(Long id, String title, String name, String description, String icon, String category, List<String> tags, boolean builtin) {
        public String engine() { return builtin ? "rule" : "ai"; }
    }
    public record Ranked(Tool tool, int score) {}

    static final int MAX_CATALOG = 100, MAX_IN_PROMPT = 40, PICK_THRESHOLD = 4, ALT_THRESHOLD = 3;
    private static final Set<String> STOP = Set.of("도구", "작성", "처리", "생성", "정리", "분석", "해줘", "해주세요", "있는", "하는", "위한", "대한", "에서", "으로", "합니다");

    private final SkillService skills;
    private final RuntimeRegistry runtime;

    public ToolCatalog(SkillService skills, RuntimeRegistry runtime) { this.skills = skills; this.runtime = runtime; }

    /** 승인되어 있고 이 사용자가 볼 수 있으며 실행 가능한 도구. 기본 제공 도구는 항상, 그 밖의 도구는 AI 실행이 켜져 있을 때만 실행할 수 있다. */
    public List<Tool> runnable(String me, boolean admin) {
        var page = skills.search(new SkillService.SearchQuery(null, null, null, null, "APPROVED", false, "popular", 0, MAX_CATALOG), me, admin);
        List<Tool> out = new ArrayList<>();
        for (SkillSummary s : page.items()) {
            boolean builtin = BuiltinSkillSeeder.trusted(s.owner(), s.name());
            if (!builtin && !runtime.aiAvailable()) continue;
            if (s.version() == null) continue; // 게시된 버전이 없으면 실행할 수 없다
            out.add(new Tool(s.id(), displayTitle(s, builtin), s.name(), s.description() == null ? "" : s.description(),
                s.icon(), s.categoryName(), s.tags() == null ? List.of() : s.tags(), builtin));
        }
        return out;
    }

    /** 화면에 보일 이름. 표시 이름이 없는 기본 제공 도구는 SKILL.md 첫 제목(# …)을 쓰고, 그것도 없으면 식별 이름을 쓴다. */
    static String displayTitle(SkillSummary s, boolean builtin) {
        if (s.title() != null && !s.title().isBlank()) return s.title();
        if (builtin) {
            for (String line : BuiltinSkillSeeder.bundledMd(s.name(), "").split("\\R")) {
                if (line.startsWith("# ") && line.length() > 2) return line.substring(2).strip();
            }
        }
        return s.name();
    }

    /** 사용자의 말과 겹치는 정도로 순위를 매긴다(이름·태그 3점, 설명 1점). 점수가 0 인 도구는 뺀다. */
    static List<Ranked> rank(String text, List<Tool> tools) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return tools.stream().map(x -> new Ranked(x, score(t, x))).filter(r -> r.score() > 0)
            .sorted(Comparator.comparingInt(Ranked::score).reversed()).toList();
    }

    static int score(String lowerText, Tool tool) {
        int sc = 0;
        for (String w : words(tool.title() + " " + tool.name())) if (lowerText.contains(w)) sc += 3;
        for (String tag : tool.tags()) { String g = tag == null ? "" : tag.toLowerCase(Locale.ROOT).trim(); if (g.length() >= 2 && lowerText.contains(g)) sc += 3; }
        for (String w : words(tool.description())) if (lowerText.contains(w)) sc += 1;
        return sc;
    }

    /** 2자 이상 낱말(한글·영문·숫자). 흔한 말은 점수에서 제외한다. */
    static Set<String> words(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 2 && !STOP.contains(w)) out.add(w);
        return out;
    }

    /** AI 에게 보여 줄 후보: 말과 겹치는 순으로 앞쪽을 우선하고, 모자라면 나머지로 채운다(프롬프트 크기 제한). */
    static List<Tool> forPrompt(String text, List<Tool> tools) {
        List<Tool> out = new ArrayList<>();
        rank(text, tools).stream().limit(MAX_IN_PROMPT).forEach(r -> out.add(r.tool()));
        for (Tool t : tools) { if (out.size() >= MAX_IN_PROMPT) break; if (!out.contains(t)) out.add(t); }
        return out;
    }
}
