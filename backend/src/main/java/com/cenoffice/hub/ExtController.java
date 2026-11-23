package com.cenoffice.hub;

import com.cenoffice.common.api.ApiException;
import com.cenoffice.hub.HubDtos.*;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * CenNavi(크롬 확장) 전용 입구. 개인 액세스 토큰 중 범위가 NAVI 인 것만 받고(MCP 토큰은 거부),
 * 웹 페이지 출처(http/https)의 요청은 막는다. 실행은 웹·MCP 와 같은 ExecutionService 를 거치므로
 * 승인 상태·권한·속도 제한·감사·이력 규칙이 그대로 적용된다. 실행 이력에는 출처 CENNAVI 와 사이트 도메인만 남는다.
 */
@RestController
@RequestMapping("/api/ext")
public class ExtController {
    public record ToolView(String name, String description) {}
    public record RunRequest(String tool, String input, String host) {}

    private final PersonalTokenService tokens;
    private final ExposedTools catalog;
    private final SkillService skills;
    private final ExecutionService executions;
    private final List<String> allowedOrigins;

    public ExtController(PersonalTokenService tokens, ExposedTools catalog, SkillService skills, ExecutionService executions,
                         @Value("${app.ext.allowed-origins:}") List<String> allowedOrigins) {
        this.tokens = tokens; this.catalog = catalog; this.skills = skills; this.executions = executions;
        this.allowedOrigins = allowedOrigins.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @GetMapping("/tools")
    public List<ToolView> tools(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin) {
        var who = who(auth, origin);
        return catalog.exposed(who).stream().map(s -> new ToolView(s.name(), s.description())).toList();
    }

    @PostMapping("/executions") @ResponseStatus(HttpStatus.ACCEPTED)
    public ExecutionView run(@RequestBody RunRequest r, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                             @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin) {
        var who = who(auth, origin);
        String name = r.tool() == null ? "" : r.tool();
        var skill = catalog.exposed(who).stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "알 수 없는 도구입니다: " + name));
        return executions.request(who.username(), false, new ExecuteRequest(skill.id(), null, r.input(), null), new ExecutionService.Origin("CENNAVI", host(r.host())));
    }

    @GetMapping("/executions/{id}")
    public ExecutionView get(@PathVariable String id, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                             @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin) {
        return executions.get(id, who(auth, origin).username(), false);
    }

    @PostMapping("/executions/{id}/cancel")
    public ExecutionView cancel(@PathVariable String id, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin) {
        return executions.cancel(id, who(auth, origin).username(), false);
    }

    @GetMapping("/ping")
    public Map<String, Object> ping(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                    @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin) {
        return Map.of("ok", true, "user", who(auth, origin).username());
    }

    private PersonalTokenService.Principal who(String auth, String origin) {
        // 웹 페이지 출처는 거부(DNS rebinding·악성 사이트 방지). 확장 출처는 설정에 목록이 있으면 그 안에서만 허용한다.
        if (origin != null && (!origin.startsWith("chrome-extension://") || (!allowedOrigins.isEmpty() && !allowedOrigins.contains(origin))))
            throw new ApiException(HttpStatus.FORBIDDEN, "허용되지 않은 출처입니다");
        String raw = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim() : null;
        return tokens.authenticate(raw, PersonalTokenService.SCOPE_NAVI)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "CenNavi용 토큰이 필요합니다. CEN HUB 'AI에 연결' 화면에서 CenNavi용 토큰을 만드세요"));
    }

    /** 사이트 도메인만 남긴다(경로·쿼리·사용자 정보는 버림). 형식이 맞지 않으면 기록하지 않는다. */
    static String host(String raw) {
        if (raw == null) return null;
        String h = raw.strip().toLowerCase();
        return h.matches("[a-z0-9]([a-z0-9.-]{0,253}[a-z0-9])?") ? h : null;
    }
}
