package com.cenoffice.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini(generateContent) 연동 (LLM_PROVIDER=gemini, GEMINI_API_KEY 필요).
 * 요구사항 원문이 외부 서비스로 전송된다. 문자열/JSON 만 돌려주며 실행 권한이 없고, 출력은 PmService 가 다시 검증한다.
 * API Key 는 URL 이 아닌 헤더(x-goog-api-key)로 보내며, 응답 본문/키는 로그·예외 메시지에 넣지 않는다.
 */
public class GeminiLlmProvider implements LlmProvider {
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;

    public GeminiLlmProvider(ObjectMapper mapper, String apiKey, String model) {
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override public String name() { return "gemini"; }

    @Override public boolean isReal() { return true; }

    @Override public String model() { return model; }

    @Override
    public String chat(String systemPrompt, String userText) throws LlmException {
        return call(systemPrompt, userText, false, false).trim();
    }

    @Override
    public String chatQuick(String systemPrompt, String userText) throws LlmException {
        return call(systemPrompt, userText, false, true).trim();
    }

    @Override
    public String generateJson(String systemPrompt, String userText) throws LlmException {
        return call(systemPrompt, userText, true, false).trim();
    }

    private String call(String system, String user, boolean json, boolean quick) throws LlmException {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
            body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))));
            body.put("generationConfig", generationConfig(json, quick));
            URI uri = URI.create(BASE + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent");
            HttpRequest r = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90))
                    .header("x-goog-api-key", apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                String status = "";
                try { status = mapper.readTree(res.body()).path("error").path("status").asText(""); } catch (Exception ignored) { }
                throw new LlmException("LLM 호출 실패 (HTTP " + res.statusCode() + (status.isEmpty() ? "" : ", " + status) + ")");
            }
            StringBuilder text = new StringBuilder();
            JsonNode cand = mapper.readTree(res.body()).path("candidates").path(0);
            for (JsonNode part : cand.path("content").path("parts"))
                if (!part.path("thought").asBoolean(false)) text.append(part.path("text").asText(""));
            // JSON 답이 출력 한도에서 잘리면 끝이 없는 JSON 이 되어 읽을 수 없다: 잘렸다고 알려 다시 시도하게 한다
            if (json && "MAX_TOKENS".equals(cand.path("finishReason").asText(""))) throw new LlmException("LLM 답이 출력 한도에서 잘렸습니다");
            if (text.length() == 0) throw new LlmException("LLM 응답이 비어 있습니다 (안전 필터 등으로 차단되었을 수 있습니다)");
            return text.toString();
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM 호출이 중단되었습니다", e);
        } catch (Exception e) {
            throw new LlmException("LLM 호출 중 오류가 발생했습니다", e);
        }
    }

    static boolean thinkingOff(String model) { return model != null && model.toLowerCase().contains("flash"); }

    /** 빠른 답(quick)은 출력을 줄이고, Flash 계열이면 답하기 전 추론(생각) 단계를 끈다(Pro 계열은 끌 수 없어 그대로 둔다) */
    Map<String, Object> generationConfig(boolean json, boolean quick) {
        Map<String, Object> gen = new LinkedHashMap<>();
        if (json) gen.put("responseMimeType", "application/json");
        // Gemini 2.5 는 '생각' 토큰도 출력 한도에 들어간다: JSON(긴 초안)은 한도를 넉넉히 두고 생각은 짧게 묶는다
        gen.put("maxOutputTokens", json ? 16384 : quick ? 400 : 800);
        if (quick && thinkingOff(model)) gen.put("thinkingConfig", Map.of("thinkingBudget", 0));
        else if (json && thinkingOff(model)) gen.put("thinkingConfig", Map.of("thinkingBudget", 1024));
        return gen;
    }
}
