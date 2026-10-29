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
    JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
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
                        .requestMatchers("/api/auth/login", "/api/auth/signup", "/ws/**").permitAll() // /ws 는 핸드셰이크 인터셉터가 토큰을 검증
                        .requestMatchers("/mcp").permitAll() // /mcp 는 컨트롤러가 개인 액세스 토큰(cen_pat_)을 직접 검증
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                        .bearerTokenResolver(request -> {
                            if ("/mcp".equals(request.getRequestURI())) return null; // 개인 액세스 토큰은 JWT 가 아니므로 JWT 필터가 파싱하지 않게 한다
                            return new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver().resolve(request);
                        }));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props.cors().allowedOrigins());
        c.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type"));
        c.setExposedHeaders(java.util.List.of("Content-Disposition")); // 다운로드 파일명을 브라우저(다른 출처)가 읽을 수 있게
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/api/**", c);
        return s;
    }
}
