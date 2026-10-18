package com.cenoffice.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(Jwt jwt, Seed seed, Cors cors, Devices devices, Llm llm) {
    /** provider: mock | anthropic. API Key 는 환경변수로만 주입한다(코드/DB 저장 금지). */
    public record Llm(String provider, String apiKey, String model, String openaiApiKey, String openaiModel, String geminiApiKey, String geminiModel) {}
    public record Jwt(String secret, long ttlMinutes) {}
    public record Seed(String adminPassword, String userPassword, boolean syncPasswords) {}
    public record Cors(List<String> allowedOrigins) {}
    public record Devices(int commandTimeoutSeconds, Mock mock) {}
    public record Mock(int minLatencyMs, int maxLatencyMs, double failureRate) {}
}
