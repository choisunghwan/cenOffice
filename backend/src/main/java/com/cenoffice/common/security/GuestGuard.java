package com.cenoffice.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 방문자(체험) 계정이 할 수 없는 일: 스킬·에이전트 등록·수정·승인 요청, 개인 액세스 토큰, 관리자 기능.
 * 보기·스킬 실행·일정·회의·홈 대화는 그대로 쓸 수 있다. 토큰의 guest 표시로 판단한다(DB 조회 없음).
 */
@Configuration
public class GuestGuard implements HandlerInterceptor, WebMvcConfigurer {
    /** 막는 쓰기 요청(GET 이외) 경로 */
    static final Pattern BLOCKED_WRITE = Pattern.compile("^/api/hub/(skills(?:/.*)?|agents(?:/.*)?|tokens(?:/.*)?)$");
    static final Pattern BLOCKED_ANY = Pattern.compile("^/api/(admin|hub/tokens)(/.*)?$");
    static final String MESSAGE = "{\"error\":\"방문자는 쓸 수 없는 기능입니다. 회원가입 후 이용해 주세요.\"}";

    @Override public void addInterceptors(InterceptorRegistry r) { r.addInterceptor(this).addPathPatterns("/api/**"); }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt)) return true;
        if (!Boolean.TRUE.equals(jwt.getToken().getClaimAsBoolean("guest"))) return true;
        String path = req.getRequestURI();
        boolean write = !"GET".equals(req.getMethod()) && !"HEAD".equals(req.getMethod());
        // 스킬 실행(/api/hub/executions)과 스킬 목록·상세 보기는 허용
        if (BLOCKED_ANY.matcher(path).matches() || (write && BLOCKED_WRITE.matcher(path).matches())) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json;charset=UTF-8");
            res.getOutputStream().write(MESSAGE.getBytes(StandardCharsets.UTF_8));
            return false;
        }
        return true;
    }
}
