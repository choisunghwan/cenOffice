package com.cenoffice.pm;

import com.cenoffice.config.AppProperties;
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
        if (c != null && "anthropic".equalsIgnoreCase(c.provider())) {
            if (c.apiKey() == null || c.apiKey().isBlank()) {
                log.warn("LLM_PROVIDER=anthropic 이지만 ANTHROPIC_API_KEY 가 없어 Mock 으로 동작합니다.");
            } else {
                log.warn("LLM 이 활성화되었습니다 ({}): 요구사항/대화 내용이 외부 서비스로 전송됩니다.", c.model());
                return new AnthropicLlmProvider(mapper, c.apiKey(), c.model());
            }
        }
        if (c != null && "openai".equalsIgnoreCase(c.provider())) {
            if (c.openaiApiKey() == null || c.openaiApiKey().isBlank()) {
                log.warn("LLM_PROVIDER=openai 이지만 OPENAI_API_KEY 가 없어 Mock 으로 동작합니다.");
            } else {
                log.warn("LLM 이 활성화되었습니다 (openai / {}): 요구사항/대화 내용이 외부 서비스로 전송됩니다.", c.openaiModel());
                return new OpenAiLlmProvider(mapper, c.openaiApiKey(), c.openaiModel());
            }
        }
        if (c != null && "gemini".equalsIgnoreCase(c.provider())) {
            if (c.geminiApiKey() == null || c.geminiApiKey().isBlank()) {
                log.warn("LLM_PROVIDER=gemini 이지만 GEMINI_API_KEY 가 없어 Mock 으로 동작합니다.");
            } else {
                log.warn("LLM 이 활성화되었습니다 (gemini / {}): 요구사항/대화 내용이 외부 서비스로 전송됩니다.", c.geminiModel());
                return new GeminiLlmProvider(mapper, c.geminiApiKey(), c.geminiModel());
            }
        }
        return new MockLlmProvider();
    }
}
