package com.cenoffice.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 연동 (LLM_PROVIDER=anthropic, ANTHROPIC_API_KEY 필요).
 * 주의: 요구사항 원문이 외부 서비스로 전송된다. 이 클래스는 문자열/JSON 만 돌려주고 어떤 실행 권한도 갖지 않는다.
 * ※ 실제 API 호출은 API Key 가 있는 환경에서만 검증할 수 있으며, 파싱 로직만 단위 테스트되어 있다.
 */
public class AnthropicLlmProvider implements LlmProvider {
    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;

    public AnthropicLlmProvider(ObjectMapper mapper, String apiKey, String model) {
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override public String name() { return "anthropic"; }

    @Override public boolean isReal() { return true; }

    @Override public String model() { return model; }

    @Override
    public String chat(String systemPrompt, String userText) throws LlmException {
        return call(systemPrompt, userText, 600).trim();
    }

    @Override
    public String generateJson(String systemPrompt, String userText) throws LlmException {
        return call(systemPrompt, userText, 4096).trim();
    }

    private String call(String system, String user, int maxTokens) throws LlmException {
        try {
            String body = mapper.writeValueAsString(Map.of("model", model, "max_tokens", maxTokens, "system", system,
                    "messages", List.of(Map.of("role", "user", "content", user))));
            HttpRequest r = HttpRequest.newBuilder(URI.create(ENDPOINT)).timeout(Duration.ofSeconds(90))
                    .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01").header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new LlmException("LLM 호출 실패 (HTTP " + res.statusCode() + ")"); // 응답 본문/키는 기록하지 않음
            JsonNode content = mapper.readTree(res.body()).path("content");
            StringBuilder text = new StringBuilder();
            for (JsonNode c : content) if ("text".equals(c.path("type").asText())) text.append(c.path("text").asText());
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
