package com.cenoffice.pm;

import com.cenoffice.pm.PmTypes.PmRequest;
import com.cenoffice.pm.PmTypes.PmResult;
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
    public PmResult planRequirements(PmRequest req) throws LlmException {
        StringBuilder user = new StringBuilder();
        user.append("프로젝트: ").append(req.projectName()).append("\n\n<requirement>\n").append(req.requirementText()).append("\n</requirement>\n");
        if (req.answers() != null && !req.answers().isEmpty()) {
            user.append("\n<answers_to_previous_questions>\n").append(String.join("\n", req.answers())).append("\n</answers_to_previous_questions>\n");
        }
        return AnthropicLlmProvider.parsePmResult(mapper, call(AnthropicLlmProvider.PM_SYSTEM, user.toString(), true));
    }

    @Override
    public String chat(String systemPrompt, String userText) throws LlmException {
        return call(systemPrompt, userText, false).trim();
    }

    @Override
    public String generateJson(String systemPrompt, String userText) throws LlmException {
        return call(systemPrompt, userText, true).trim();
    }

    private String call(String system, String user, boolean json) throws LlmException {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
            body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))));
            Map<String, Object> gen = new LinkedHashMap<>();
            if (json) gen.put("responseMimeType", "application/json");
            gen.put("maxOutputTokens", json ? 8192 : 800);
            body.put("generationConfig", gen);
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
            for (JsonNode part : mapper.readTree(res.body()).path("candidates").path(0).path("content").path("parts"))
                text.append(part.path("text").asText(""));
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
}
