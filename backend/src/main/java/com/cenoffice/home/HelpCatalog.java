package com.cenoffice.home;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 서비스 사용법 도움말(resources/assistant-help/*.md). 입력창 AI 가 "이건 어떻게 해요?" 같은 질문에 지어내지 않고 답할 수 있게,
 * 질문과 맞는 문서만 골라 근거로 준다. 문서 형식: 첫 줄 "# 제목", "질문: 예시 질문", "키워드: 쉼표로 구분", 빈 줄 다음이 본문.
 */
@Component
public class HelpCatalog {
    private static final Logger log = LoggerFactory.getLogger(HelpCatalog.class);

    public record Doc(String id, String title, String question, List<String> keywords, String body) {}
    public record Hit(Doc doc, int score) {}

    /** 이 점수 이상일 때만 질문과 관련 있다고 본다(키워드 한 개가 맞으면 3점) */
    static final int MIN_SCORE = 3;
    private static final Set<String> STOP = Set.of("어떻게", "어디", "어디서", "언제", "무엇", "뭐야", "뭔가", "알려", "알려줘", "해줘", "하나요", "있나요", "있어요", "있어", "되나요", "가능", "해야", "되는데", "하면", "해요", "되요", "그냥", "같은", "에서", "으로");

    private final List<Doc> docs;

    public HelpCatalog() { this.docs = load(); }

    /** 테스트나 다른 구성에서 문서를 직접 줄 때 */
    HelpCatalog(List<Doc> docs) { this.docs = List.copyOf(docs); }

    private static List<Doc> load() {
        var out = new ArrayList<Doc>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:assistant-help/*.md")) {
                String name = r.getFilename() == null ? "" : r.getFilename().replaceFirst("\\.md$", "");
                try (var in = r.getInputStream()) { parse(name, new String(in.readAllBytes(), StandardCharsets.UTF_8)).ifPresent(out::add); }
            }
        } catch (IOException e) {
            log.warn("도움말 문서를 읽지 못했습니다: {}", e.toString());
        }
        out.sort(Comparator.comparing(Doc::id));
        return out;
    }

    static java.util.Optional<Doc> parse(String id, String raw) {
        String title = null, question = "";
        var keywords = new ArrayList<String>();
        var body = new StringBuilder();
        boolean inBody = false;
        for (String line : raw.replace("\r\n", "\n").split("\n")) {
            if (title == null && line.startsWith("# ")) { title = line.substring(2).strip(); continue; }
            if (!inBody && line.startsWith("질문:")) { question = line.substring(3).strip(); continue; }
            if (!inBody && line.startsWith("키워드:")) { for (String k : line.substring(4).split(",")) if (!k.isBlank()) keywords.add(k.strip().toLowerCase(Locale.ROOT)); continue; }
            if (!inBody && line.isBlank()) { inBody = true; continue; }
            if (inBody || title != null) { inBody = true; body.append(line).append('\n'); }
        }
        if (title == null || body.toString().isBlank()) return java.util.Optional.empty();
        return java.util.Optional.of(new Doc(id, title, question, List.copyOf(keywords), body.toString().strip()));
    }

    /** 이 서비스에서 할 수 있는 일의 요약(모든 답변에 기본으로 곁들인다) */
    public String overview() { return docs.stream().filter(d -> d.id().equals("overview")).findFirst().map(Doc::body).orElse(""); }

    public List<Doc> all() { return docs; }

    /** 질문과 가장 잘 맞는 문서들(개요 문서는 제외). 점수가 낮으면 비어 있다. */
    public List<Hit> search(String question, int limit) {
        List<String> tokens = tokens(question);
        if (tokens.isEmpty()) return List.of();
        var hits = new ArrayList<Hit>();
        for (Doc d : docs) {
            if (d.id().equals("overview")) continue;
            int score = score(d, tokens);
            if (score >= MIN_SCORE) hits.add(new Hit(d, score));
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed().thenComparing(h -> h.doc().id()));
        return hits.stream().limit(limit).toList();
    }

    /** 질문을 낱말로 나눈다. 조사가 붙은 말("회의록을")도 맞도록 말끝을 한두 글자 뗀 모양도 함께 본다. */
    static List<String> tokens(String q) {
        var out = new LinkedHashSet<String>();
        if (q == null) return List.of();
        for (String t : q.toLowerCase(Locale.ROOT).split("[^0-9a-z가-힣]+")) {
            if (t.length() < 2 || STOP.contains(t)) continue;
            out.add(t);
            if (t.length() >= 3) out.add(t.substring(0, t.length() - 1));
            if (t.length() >= 4) out.add(t.substring(0, t.length() - 2));
        }
        return List.copyOf(out);
    }

    private static int score(Doc d, List<String> tokens) {
        String title = d.title().toLowerCase(Locale.ROOT), body = d.body().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String t : tokens) {
            int s = 0;
            for (String k : d.keywords()) if (k.contains(t) || (k.length() >= 2 && t.contains(k))) { s = 3; break; } // 키워드가 맞으면 3점
            if (s == 0 && title.contains(t)) s = 2;
            if (s == 0 && body.contains(t)) s = 1;
            score += s;
        }
        return score;
    }

    /** 이어서 물어볼 만한 질문들(관련 문서의 예시 질문). 첫 문서는 방금 답한 것이라 건너뛸 수 있다. */
    public List<String> followUps(List<Hit> hits, int skip, int limit) {
        return hits.stream().skip(skip).map(h -> h.doc().question()).filter(q -> !q.isBlank()).distinct().limit(limit).toList();
    }

    /** 아무 문서도 맞지 않을 때 보여 줄 기본 예시 */
    public static final List<String> DEFAULT_SUGGESTIONS = List.of("쓸만한 도구 뭐 있어?", "내일 오후 3시에 팀 회의 잡아줘", "회의록 다운로드해줘", "양식은 어떻게 올리고 어떻게 만들어야 채워져요?");
}
