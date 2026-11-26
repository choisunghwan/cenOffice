package com.cenoffice.common.security;

import com.cenoffice.common.config.AppProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecretKey jwtKey(AppProperties props) {
        String secret = props.jwt().secret();
        byte[] key;
        if (secret == null || secret.isBlank()) {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            log.warn("JWT_SECRET 이 설정되지 않아 임의 키를 생성했습니다. 서버를 재시작하면 기존 로그인이 만료됩니다.");
        } else {
            key = secret.getBytes(StandardCharsets.UTF_8);
            if (key.length < 32) throw new IllegalStateException("JWT_SECRET 은 32자 이상이어야 합니다.");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, com.cenoffice.common.repo.UserRepository users) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // 토큰은 서버에 저장하지 않으므로, 관리자가 삭제한 계정의 토큰이 만료 전까지 쓰이지 않게 계정이 아직 있는지 확인한다
        var exists = (org.springframework.security.oauth2.core.OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt>) jwt ->
            jwt.getSubject() != null && users.existsByUsername(jwt.getSubject())
                ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
                : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error("invalid_token", "삭제된 계정입니다", null));
        d.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(org.springframework.security.oauth2.jwt.JwtValidators.createDefault(), exists));
        return d;
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/signup", "/api/auth/guest", "/ws/**").permitAll() // /ws 는 핸드셰이크 인터셉터가 토큰을 검증
                        .requestMatchers("/mcp", "/api/ext/**").permitAll() // 컨트롤러가 개인 액세스 토큰(cen_pat_)을 직접 검증
                        // 화면 파일(index.html, /assets 등): 백엔드가 화면도 함께 제공하는 배포(Render)용. API·MCP·WS 는 위 규칙을 그대로 따른다
                        .requestMatchers(r -> "GET".equals(r.getMethod()) && !isApiPath(r.getRequestURI())).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                        .bearerTokenResolver(request -> {
                            if ("/mcp".equals(request.getRequestURI()) || request.getRequestURI().startsWith("/api/ext/")) return null; // 개인 액세스 토큰은 JWT 가 아니므로 JWT 필터가 파싱하지 않게 한다
                            return new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver().resolve(request);
                        }));
        return http.build();
    }

    static boolean isApiPath(String uri) {
        return uri.startsWith("/api/") || uri.equals("/api") || uri.startsWith("/mcp") || uri.startsWith("/ws") || uri.startsWith("/error");
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props.cors().allowedOrigins());
        c.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type"));
        c.setExposedHeaders(java.util.List.of("Content-Disposition")); // 다운로드 파일명을 브라우저(다른 출처)가 읽을 수 있게
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        // CenNavi 확장 전용 입구: 확장 출처만 CORS 를 통과시키고(웹 출처는 거부), 세부 허용 목록은 ExtController 가 다시 검사한다.
        // 먼저 등록한 설정이 우선하므로 더 좁은 /api/ext/** 를 /api/** 보다 앞에 둔다.
        CorsConfiguration ext = new CorsConfiguration();
        ext.setAllowedOriginPatterns(java.util.List.of("chrome-extension://*"));
        ext.setAllowedMethods(java.util.List.of("GET", "POST", "OPTIONS"));
        ext.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type"));
        s.registerCorsConfiguration("/api/ext/**", ext);
        s.registerCorsConfiguration("/api/**", c);
        return s;
    }
}
