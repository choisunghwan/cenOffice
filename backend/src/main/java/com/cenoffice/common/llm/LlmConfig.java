package com.cenoffice.common.llm;

import com.cenoffice.common.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {
    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    LlmProvider llmProvider(AppProperties props, ObjectMapper mapper) {
        AppProperties.Llm c = props.llm();
        // 대시보드에 붙여 넣을 때 들어간 앞뒤 공백·따옴표 때문에 조용히 Mock 이 되지 않게 다듬는다
        String provider = c == null ? "" : clean(c.provider()).toLowerCase();
        String anthropicKey = c == null ? "" : clean(c.apiKey()), openaiKey = c == null ? "" : clean(c.openaiApiKey()), geminiKey = c == null ? "" : clean(c.geminiApiKey());
        if ("anthropic".equals(provider)) {
            if (anthropicKey.isEmpty()) {
                log.warn("LLM_PROVIDER=anthropic 이지만 ANTHROPIC_API_KEY 가 없어 Mock 으로 동작합니다.");
            } else {
                log.warn("LLM 이 활성화되었습니다 ({}): 요구사항/대화 내용이 외부 서비스로 전송됩니다.", c.model());
                return new AnthropicLlmProvider(mapper, anthropicKey, c.model());
            }
        }
        if ("openai".equals(provider)) {
            if (openaiKey.isEmpty()) {
                log.warn("LLM_PROVIDER=openai 이지만 OPENAI_API_KEY 가 없어 Mock 으로 동작합니다.");
            } else {
                log.warn("LLM 이 활성화되었습니다 (openai / {}): 요구사항/대화 내용이 외부 서비스로 전송됩니다.", c.openaiModel());
                return new OpenAiLlmProvider(mapper, openaiKey, c.openaiModel());
            }
        }
        if ("gemini".equals(provider)) {
            if (geminiKey.isEmpty()) {
                log.warn("LLM_PROVIDER=gemini 이지만 GEMINI_API_KEY 가 없어 Mock 으로 동작합니다.");
            } else {
                log.warn("LLM 이 활성화되었습니다 (gemini / {}): 요구사항/대화 내용이 외부 서비스로 전송됩니다.", c.geminiModel());
                return new GeminiLlmProvider(mapper, geminiKey, c.geminiModel());
            }
        }
        if (!provider.isEmpty() && !provider.equals("mock"))
            log.warn("LLM_PROVIDER='{}' 는 알 수 없는 값이라 Mock 으로 동작합니다. anthropic / openai / gemini 중 하나로 넣으세요.", provider);
        else log.info("LLM 미연결(Mock): LLM_PROVIDER 를 anthropic / openai / gemini 로 넣으면 AI 가 연결됩니다.");
        return new MockLlmProvider();
    }

    private static String clean(String v) { return v == null ? "" : v.strip().replaceAll("^[\"']+|[\"']+$", "").strip(); }
}
