package com.cenoffice.hub;

import com.cenoffice.hub.domain.HubSkill;

import com.cenoffice.common.api.ApiException;
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
    private final ExecutionService executions;
    private final ObjectMapper json;

    public McpController(PersonalTokenService tokens, SkillService skills, ExecutionService executions, ObjectMapper json) {
        this.tokens = tokens; this.skills = skills; this.executions = executions; this.json = json;
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
                    ObjectNode caps = res.putObject("capabilities");
                    caps.putObject("tools").put("listChanged", false);
                    caps.putObject("prompts").put("listChanged", false);
                    res.putObject("serverInfo").put("name", "cen-ai-hub").put("version", "1.0.0");
                    res.put("instructions", "CEN OFFICE AI HUB 의 승인된 업무 도구입니다. 결과는 검토용 초안이며 개인정보·비밀번호는 입력하지 마세요.");
                }
                case "ping" -> { }
                case "tools/list" -> res.set("tools", tools(who));
                case "tools/call" -> res = call(req.path("params"), who);
                case "prompts/list" -> res.set("prompts", prompts(who));
                case "prompts/get" -> res = prompt(req.path("params"), who);
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

    /** 이 사용자가 MCP 로 쓸 수 있는 승인된 도구(tools/list 와 prompts/list 가 같은 목록을 쓴다). */
    private List<SkillSummary> exposed(PersonalTokenService.Principal who) {
        List<SkillSummary> out = new ArrayList<>();
        int page = 0;
        while (page < 10) {
            var res = skills.search(new SkillService.SearchQuery(null, null, null, null, "APPROVED", false, null, page++, 100), who.username(), who.admin());
            for (SkillSummary s : res.items()) {
                if (s.sample() || !s.name().matches("[A-Za-z0-9_-]{1,64}")) continue;
                out.add(s);
            }
            if (res.items().size() < 100) break;
        }
        return out;
    }

    private ArrayNode tools(PersonalTokenService.Principal who) {
        ArrayNode arr = json.createArrayNode();
        for (SkillSummary s : exposed(who)) {
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
        return arr;
    }

    /** 슬래시 명령(/mcp__cen-ai-hub__이름)으로 보이게 하는 프롬프트 목록. 도구마다 하나씩, 인자는 원문(input) 하나. */
    private ArrayNode prompts(PersonalTokenService.Principal who) {
        ArrayNode arr = json.createArrayNode();
        for (SkillSummary s : exposed(who)) {
            ObjectNode p = arr.addObject();
            p.put("name", s.name());
            p.put("description", s.description());
            p.putArray("arguments").addObject().put("name", "input").put("description", "처리할 원문(선택). 길거나 여러 줄이면 비워 두고 다음 메시지로 붙여 넣으세요").put("required", false);
        }
        return arr;
    }

    /** 프롬프트는 실행하지 않고 "이 도구로 처리해 줘"라는 요청문만 만든다. 실행은 기존 tools/call 경로(권한·승인·감사)를 그대로 탄다. */
    private ObjectNode prompt(JsonNode params, PersonalTokenService.Principal who) {
        String name = params.path("name").asText("");
        if (exposed(who).stream().noneMatch(s -> s.name().equals(name))) throw new ApiException(HttpStatus.NOT_FOUND, "알 수 없는 도구입니다: " + name);
        String input = params.path("arguments").path("input").asText("").trim();
        // 슬래시 뒤 글은 공백으로 잘려 첫 단어만 오므로 원문은 선택이다. 없으면 이어서 붙여 넣을 원문을 기다리게 한다.
        String text = input.isEmpty()
                ? "cen-ai-hub 의 " + name + " 도구를 쓸 거야. 처리할 원문은 이 다음 메시지로 붙여 넣을 테니, 받으면 그 전체를 이 도구로 처리하고 결과를 알려줘. 지금은 원문을 기다린다고만 짧게 답해."
                : "cen-ai-hub 의 " + name + " 도구로 아래 내용을 처리하고 결과를 알려줘.\n\n" + input;
        ObjectNode res = json.createObjectNode();
        res.put("description", name);
        ObjectNode msg = res.putArray("messages").addObject();
        msg.put("role", "user");
        msg.putObject("content").put("type", "text").put("text", text);
        return res;
    }

    private ObjectNode call(JsonNode params, PersonalTokenService.Principal who) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        String input = args.path("input").asText("");
        String engine = args.path("engine").asText("");
        HubSkill s = skills.findByName(name).orElse(null);
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
