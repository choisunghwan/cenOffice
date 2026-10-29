package com.cenoffice.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 연동 (LLM_PROVIDER=openai, OPENAI_API_KEY 필요).
 * 요구사항 원문이 외부 서비스로 전송된다. 이 클래스는 문자열/JSON 만 돌려주며 어떤 실행 권한도 갖지 않는다.
 * 출력은 PmService 가 PlanValidator 로 다시 검증한다. API Key 와 응답 본문은 로그/예외 메시지에 넣지 않는다.
 */
public class OpenAiLlmProvider implements LlmProvider {
    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;

    public OpenAiLlmProvider(ObjectMapper mapper, String apiKey, String model) {
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override public String name() { return "openai"; }

    @Override public boolean isReal() { return true; }

    @Override public String model() { return model; }

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
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
            if (json) body.put("response_format", Map.of("type", "json_object"));
            HttpRequest r = HttpRequest.newBuilder(URI.create(ENDPOINT)).timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                String type = "";
                try { type = mapper.readTree(res.body()).path("error").path("code").asText(""); } catch (Exception ignored) { }
                throw new LlmException("LLM 호출 실패 (HTTP " + res.statusCode() + (type.isEmpty() ? "" : ", " + type) + ")");
            }
            JsonNode content = mapper.readTree(res.body()).path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) throw new LlmException("LLM 응답이 비어 있습니다");
            return content.asText();
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
