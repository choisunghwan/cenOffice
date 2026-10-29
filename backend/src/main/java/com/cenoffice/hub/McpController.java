package com.cenoffice.hub;

import com.cenoffice.hub.domain.HubSkill;
import com.cenoffice.hub.domain.HubSkillRepository;

import com.cenoffice.api.ApiException;
import com.cenoffice.hub.HubDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * MCP(Model Context Protocol) 서버 — Streamable HTTP 의 단일 JSON 응답 방식.
 * Claude Code / Codex 등이 개인 액세스 토큰(Bearer cen_pat_…)으로 연결해 AI HUB 의 승인된 Skill 을 도구로 호출한다.
 * 호출은 기존 실행 서비스를 그대로 거치므로 권한·승인 상태·속도 제한·감사·KPI 규칙이 웹과 동일하다.
 */
@RestController
public class McpController {
    private static final String PROTOCOL = "2025-03-26";
    private static final Duration WAIT = Duration.ofSeconds(90);

    private final PersonalTokenService tokens;
    private final SkillService skills;
    private final HubSkillRepository skillRepo;
    private final ExecutionService executions;
    private final ObjectMapper json;

    public McpController(PersonalTokenService tokens, SkillService skills, HubSkillRepository skillRepo, ExecutionService executions, ObjectMapper json) {
        this.tokens = tokens; this.skills = skills; this.skillRepo = skillRepo; this.executions = executions; this.json = json;
    }

    @RequestMapping(value = "/mcp", method = {RequestMethod.GET, RequestMethod.DELETE})
    public ResponseEntity<Void> unsupported() { return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build(); }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> rpc(@RequestBody JsonNode req, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                 @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin) {
        if (origin != null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build(); // 브라우저 출처 요청 차단(DNS rebinding 방지)
        String raw = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim() : null;
        var who = tokens.authenticate(raw).orElse(null);
        if (who == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").build();
        if (!req.isObject()) return ResponseEntity.ok(error(null, -32600, "Invalid Request"));
        String method = req.path("method").asText("");
        JsonNode id = req.get("id");
        if (id == null || id.isNull()) return ResponseEntity.accepted().build(); // notification (예: notifications/initialized)
        try {
            ObjectNode res = json.createObjectNode();
            switch (method) {
                case "initialize" -> {
                    res.put("protocolVersion", PROTOCOL);
                    res.putObject("capabilities").putObject("tools").put("listChanged", false);
                    res.putObject("serverInfo").put("name", "cen-ai-hub").put("version", "1.0.0");
                    res.put("instructions", "CEN OFFICE AI HUB 의 승인된 업무 도구입니다. 결과는 검토용 초안이며 개인정보·비밀번호는 입력하지 마세요.");
                }
                case "ping" -> { }
                case "tools/list" -> res.set("tools", tools(who));
                case "tools/call" -> res = call(req.path("params"), who);
                default -> { return ResponseEntity.ok(error(id, -32601, "Method not found")); }
            }
            ObjectNode out = json.createObjectNode();
            out.put("jsonrpc", "2.0");
            out.set("id", id);
            out.set("result", res);
            return ResponseEntity.ok(out);
        } catch (ApiException e) {
            return ResponseEntity.ok(error(id, -32602, e.getMessage()));
        }
    }

    private ObjectNode error(JsonNode id, int code, String msg) {
        ObjectNode o = json.createObjectNode();
        o.put("jsonrpc", "2.0");
        if (id == null) o.putNull("id"); else o.set("id", id);
        o.putObject("error").put("code", code).put("message", msg);
        return o;
    }

    private ArrayNode tools(PersonalTokenService.Principal who) {
        ArrayNode arr = json.createArrayNode();
        int page = 0;
        while (page < 10) {
            var res = skills.search(new SkillService.SearchQuery(null, null, null, null, "APPROVED", false, null, page++, 100), who.username(), who.admin());
            for (SkillSummary s : res.items()) {
                if (s.sample() || !s.name().matches("[A-Za-z0-9_-]{1,64}")) continue;
                ObjectNode t = arr.addObject();
                t.put("name", s.name());
                t.put("description", s.description());
                ObjectNode schema = t.putObject("inputSchema");
                schema.put("type", "object");
                ObjectNode props = schema.putObject("properties");
                props.putObject("input").put("type", "string").put("description", "처리할 원문(로그, SQL, 메모, 요구사항 등). 최대 15,000자");
                props.putObject("engine").put("type", "string").put("description", "rule(규칙 기반, 외부 전송 없음) 또는 ai(AI 모델). 생략하면 기본 실행기").putArray("enum").add("rule").add("ai");
                schema.putArray("required").add("input");
            }
            if (res.items().size() < 100) break;
        }
        return arr;
    }

    private ObjectNode call(JsonNode params, PersonalTokenService.Principal who) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        String input = args.path("input").asText("");
        String engine = args.path("engine").asText("");
        HubSkill s = skillRepo.findByName(name).orElse(null);
        if (s == null || !skills.canView(s, who.username(), who.admin()) || !"APPROVED".equals(s.status)) throw new ApiException(HttpStatus.NOT_FOUND, "알 수 없는 도구입니다: " + name);
        ObjectNode result = json.createObjectNode();
        ArrayNode content = result.putArray("content");
        try {
            var started = executions.request(who.username(), who.admin(), new ExecuteRequest(s.id, null, input, engine.isBlank() ? null : engine));
            Instant deadline = Instant.now().plus(WAIT);
            ExecutionView v = started;
            while (!List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(v.status()) && Instant.now().isBefore(deadline)) {
                Thread.sleep(300);
                v = executions.get(started.id(), who.username(), who.admin());
            }
            boolean ok = "SUCCEEDED".equals(v.status()) && v.result() != null;
            content.addObject().put("type", "text").put("text", ok ? v.result() : "실행 실패: " + Objects.requireNonNullElse(v.error(), "시간이 초과되었거나 결과가 없습니다"));
            result.put("isError", !ok);
        } catch (ApiException e) {
            content.addObject().put("type", "text").put("text", e.getMessage());
            result.put("isError", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            content.addObject().put("type", "text").put("text", "요청이 중단되었습니다");
            result.put("isError", true);
        }
        return result;
    }
}
