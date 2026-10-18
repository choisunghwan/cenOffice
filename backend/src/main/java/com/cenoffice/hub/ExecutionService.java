package com.cenoffice.hub;

import com.cenoffice.api.ApiException;
import com.cenoffice.api.AuditService;
import com.cenoffice.hub.HubDtos.ExecuteRequest;
import com.cenoffice.hub.HubDtos.ExecutionView;
import com.cenoffice.hub.HubDtos.PageView;
import com.cenoffice.hub.runtime.AgentRuntimeAdapter;
import com.cenoffice.hub.runtime.AgentRuntimeAdapter.RuntimeFailure;
import com.cenoffice.hub.runtime.AgentRuntimeAdapter.RuntimeRequest;
import com.cenoffice.hub.runtime.AgentRuntimeAdapter.RuntimeStatus;
import com.cenoffice.hub.runtime.RuntimeRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 실행 요청/상태/결과/취소/이력. 런타임이 없으면 요청을 접수하지 않는다(RUNTIME_NOT_CONFIGURED).
 * KPI 집계 규칙: 시뮬레이션 런타임, 샘플 Skill/Agent, 제작자 본인 실행은 counted=false. 같은 입력의 10초 이내 중복은 거부한다.
 * 모든 요청/종료는 감사 로그와 사용 이벤트(실행당 Skill 별 1건, 유니크 제약)로 남는다.
 */
@Service
public class ExecutionService {
    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);
    private static final Duration DUP_WINDOW = Duration.ofSeconds(10);
    private static final int RATE_PER_MINUTE = 10;
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(120);

    private final HubExecutionRepository executions;
    private final HubUsageEventRepository usage;
    private final HubSkillVersionRepository versions;
    private final HubSkillRepository skills;
    private final SkillService skillService;
    private final AgentService agentService;
    private final RuntimeRegistry runtime;
    private final AuditService audit;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();

    public ExecutionService(HubExecutionRepository executions, HubUsageEventRepository usage, HubSkillVersionRepository versions, HubSkillRepository skills,
                            SkillService skillService, AgentService agentService, RuntimeRegistry runtime, AuditService audit) {
        this.executions = executions;
        this.usage = usage;
        this.versions = versions;
        this.skills = skills;
        this.skillService = skillService;
        this.agentService = agentService;
        this.runtime = runtime;
        this.audit = audit;
    }

    public ExecutionView request(String user, boolean admin, ExecuteRequest r) {
        return request(user, admin, r, false);
    }

    ExecutionView requestDocuments(String user, boolean admin, ExecuteRequest r) {
        return request(user, admin, r, true);
    }

    private ExecutionView request(String user, boolean admin, ExecuteRequest r, boolean documents) {
        if (r == null) throw new ApiException(HttpStatus.BAD_REQUEST, "실행 요청이 필요합니다");
        HubSkill requested = r.skillId() == null ? null : skillService.visible(r.skillId(), user, admin);
        if (documents && (requested == null || !BuiltinSkillSeeder.trusted(requested)))
            throw new ApiException(HttpStatus.BAD_REQUEST, "내장 도구에만 문서를 입력할 수 있습니다");
        boolean useAi = "ai".equalsIgnoreCase(r.engine());
        if (useAi && (documents || requested == null)) throw new ApiException(HttpStatus.BAD_REQUEST, "AI 모델 실행은 Skill 단건에서만 사용할 수 있습니다");
        if (useAi && !runtime.aiAvailable()) throw new ApiException(HttpStatus.CONFLICT, "AI 모델 연동이 꺼져 있습니다. 관리자에게 문의하세요");
        AgentRuntimeAdapter rt = requested == null ? runtime.current() : runtime.forSkill(requested, useAi);
        if (rt.state() != AgentRuntimeAdapter.State.RUNTIME_AVAILABLE)
            throw new ApiException(HttpStatus.CONFLICT, "RUNTIME_NOT_CONFIGURED: 실행 연동 준비 중입니다. 등록·조회·다운로드만 가능합니다");
        if ((r.skillId() == null) == (r.agentId() == null)) throw new ApiException(HttpStatus.BAD_REQUEST, "skillId 또는 agentId 중 하나만 지정하세요");
        String input = r.input() == null ? "" : r.input().replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        int maxInput = documents || (requested != null && BuiltinSkillSeeder.trusted(requested)) ? 15000 : 4000;
        if (input.isEmpty() || input.length() > maxInput) throw new ApiException(HttpStatus.BAD_REQUEST, "입력은 1~" + maxInput + "자여야 합니다");

        // 대상 확인(권한·승인 상태)과 지침 수집
        List<HubSkill> targetSkills = new ArrayList<>();
        List<HubSkillVersion> targetVersions = new ArrayList<>();
        String targetName;
        boolean counted;
        String targetType;
        if (r.skillId() != null) {
            HubSkill s = skillService.visible(r.skillId(), user, admin);
            if (!"APPROVED".equals(s.status) || s.publishedVersionId == null) throw new ApiException(HttpStatus.CONFLICT, "승인된 Skill 만 실행할 수 있습니다");
            targetSkills.add(s);
            targetVersions.add(versions.findById(s.publishedVersionId).orElseThrow());
            targetName = s.name; targetType = "SKILL";
            counted = !rt.simulated() && !s.sample && !s.owner.equals(user);
        } else {
            HubAgent a = agentService.visible(r.agentId(), user, admin);
            if (!"APPROVED".equals(a.status)) throw new ApiException(HttpStatus.CONFLICT, "승인된 Agent 만 실행할 수 있습니다");
            for (Long sid : agentService.skillIds(a.id)) {
                HubSkill s = skills.findById(sid).orElse(null);
                // 요청자가 볼 수 없거나 더는 승인 상태가 아닌 Skill 은 실행 지침에서 제외한다(권한 우회 방지)
                if (s == null || !"APPROVED".equals(s.status) || s.publishedVersionId == null || !skillService.canView(s, user, admin)) continue;
                targetSkills.add(s);
                targetVersions.add(versions.findById(s.publishedVersionId).orElseThrow());
            }
            if (targetSkills.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "실행할 수 있는 Skill 이 연결되어 있지 않습니다");
            targetName = a.name; targetType = "AGENT";
            counted = !rt.simulated() && !a.sample && !a.owner.equals(user);
        }

        String hash = sha(input);
        Instant now = Instant.now();
        boolean dup = r.skillId() != null
                ? executions.existsByRequesterAndSkillIdAndAgentIdIsNullAndInputHashAndRequestedAtAfter(user, r.skillId(), hash, now.minus(DUP_WINDOW))
                : executions.existsByRequesterAndAgentIdAndInputHashAndRequestedAtAfter(user, r.agentId(), hash, now.minus(DUP_WINDOW));
        if (dup) throw new ApiException(HttpStatus.CONFLICT, "같은 요청이 방금 접수되었습니다. 잠시 후 다시 시도하세요");
        if (executions.countByRequesterAndRequestedAtAfter(user, now.minusSeconds(60)) >= RATE_PER_MINUTE)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "실행 요청이 너무 많습니다. 잠시 후 다시 시도하세요");

        HubExecution e = new HubExecution();
        e.id = UUID.randomUUID().toString();
        e.targetType = targetType; e.skillId = r.skillId(); e.skillVersionId = r.skillId() != null ? targetVersions.get(0).id : null; e.agentId = r.agentId();
        e.requester = user; e.inputHash = hash; e.input = input; e.status = "REQUESTED"; e.runtimeKind = rt.kind();
        e.simulated = rt.simulated(); e.counted = counted; e.requestedAt = now;
        executions.save(e);
        for (int i = 0; i < targetSkills.size(); i++) event(targetSkills.get(i), targetVersions.get(i).id, e, "EXECUTION_REQUESTED");
        audit.log(user, "HUB_EXECUTION_REQUESTED", "hub-exec:" + e.id, targetType + " " + targetName + " [" + rt.kind() + (rt.simulated() ? ", 시뮬레이션" : "") + "]");

        List<String> instructions = new ArrayList<>();
        for (int i = 0; i < targetVersions.size(); i++) instructions.add(BuiltinSkillSeeder.trusted(targetSkills.get(i)) ? BuiltinSkillSeeder.bundledMd(targetSkills.get(i).name, targetVersions.get(i).skillMd) : targetVersions.get(i).skillMd);
        RuntimeRequest req = new RuntimeRequest(e.id, targetType, targetName, instructions, input, user);
        exec.submit(() -> run(e.id, req, rt, targetSkills, targetVersions));
        return view(e);
    }

    private void run(String id, RuntimeRequest req, AgentRuntimeAdapter rt, List<HubSkill> ss, List<HubSkillVersion> vs) {
        try {
            HubExecution e = executions.findById(id).orElseThrow();
            e.externalId = rt.submit(req);
            e.status = "RUNNING"; e.startedAt = Instant.now();
            executions.save(e);
            Instant deadline = Instant.now().plus(RUN_TIMEOUT);
            RuntimeStatus st;
            while (true) {
                st = rt.poll(e.externalId);
                if (st.terminal() || Instant.now().isAfter(deadline)) break;
                Thread.sleep(150);
            }
            if (!st.terminal()) st = new RuntimeStatus("FAILED", null, "실행 시간이 초과되었습니다");
            finish(id, st, ss, vs);
        } catch (RuntimeFailure rf) {
            finish(id, new RuntimeStatus("FAILED", null, rf.getMessage()), ss, vs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.error("AI HUB 실행 오류", ex);
            finish(id, new RuntimeStatus("FAILED", null, "실행 중 내부 오류가 발생했습니다"), ss, vs);
        }
    }

    /** 종료 처리: 이미 종료(예: 취소)된 실행은 덮어쓰지 않는다. */
    private synchronized void finish(String id, RuntimeStatus st, List<HubSkill> ss, List<HubSkillVersion> vs) {
        HubExecution e = executions.findById(id).orElse(null);
        if (e == null || e.completedAt != null) return;
        e.status = st.state();
        e.result = st.output() == null ? null : SkillService.limit(st.output(), 20000);
        e.error = st.error() == null ? null : SkillService.limit(st.error(), 500);
        e.completedAt = Instant.now();
        executions.save(e);
        String type = st.state().equals("SUCCEEDED") ? "EXECUTION_SUCCEEDED" : st.state().equals("CANCELLED") ? "EXECUTION_CANCELLED" : "EXECUTION_FAILED";
        for (int i = 0; i < ss.size(); i++) event(ss.get(i), vs.get(i).id, e, type);
        audit.log(e.requester, "HUB_EXECUTION_FINISHED", "hub-exec:" + id, st.state() + (st.error() == null ? "" : " — " + SkillService.limit(st.error(), 120)));
    }

    public ExecutionView cancel(String id, String user, boolean admin) {
        HubExecution e = owned(id, user, admin);
        AgentRuntimeAdapter rt = runtime.current();
        if (e.completedAt != null) throw new ApiException(HttpStatus.CONFLICT, "이미 종료된 실행입니다");
        if (!rt.supportsCancel() || e.externalId == null || !rt.kind().equals(e.runtimeKind))
            throw new ApiException(HttpStatus.CONFLICT, "이 런타임은 실행 취소를 지원하지 않습니다");
        if (!rt.cancel(e.externalId)) throw new ApiException(HttpStatus.CONFLICT, "취소할 수 없는 상태입니다");
        List<HubSkill> ss = skillsOf(e);
        finish(id, new RuntimeStatus("CANCELLED", null, null), ss, ss.stream().map(s -> versions.findById(Objects.requireNonNullElse(e.skillVersionId, s.publishedVersionId)).orElse(null)).filter(Objects::nonNull).toList());
        audit.log(user, "HUB_EXECUTION_CANCELLED", "hub-exec:" + id, null);
        return view(executions.findById(id).orElseThrow());
    }

    public ExecutionView get(String id, String user, boolean admin) { return view(owned(id, user, admin)); }

    /** 본인 실행 이력(관리자는 전체). 입력/결과는 요청자와 관리자만 볼 수 있다. */
    public PageView<ExecutionView> list(String user, boolean admin, Long skillId, int page, int size) {
        int sz = Math.min(Math.max(size, 1), 100), pg = Math.max(page, 0);
        List<HubExecution> all;
        if (skillId != null) {
            skillService.visible(skillId, user, admin);
            all = executions.findBySkillIdOrderByRequestedAtDesc(skillId, PageRequest.of(0, 500)).stream().filter(x -> admin || x.requester.equals(user)).toList();
        } else if (admin) {
            all = executions.findAll().stream().sorted(Comparator.comparing((HubExecution x) -> x.requestedAt).reversed()).limit(500).toList();
        } else {
            all = executions.findByRequesterOrderByRequestedAtDesc(user, PageRequest.of(0, 500));
        }
        int from = Math.min(pg * sz, all.size());
        return new PageView<>(all.subList(from, Math.min(from + sz, all.size())).stream().map(this::view).toList(), all.size(), pg, sz);
    }

    private HubExecution owned(String id, String user, boolean admin) {
        HubExecution e = executions.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "실행을 찾을 수 없습니다"));
        if (!(admin || e.requester.equals(user))) throw new ApiException(HttpStatus.NOT_FOUND, "실행을 찾을 수 없습니다"); // 존재 여부 숨김
        return e;
    }

    private List<HubSkill> skillsOf(HubExecution e) {
        if (e.skillId != null) return skills.findById(e.skillId).map(List::of).orElse(List.of());
        List<HubSkill> out = new ArrayList<>();
        for (Long sid : agentService.skillIds(e.agentId)) skills.findById(sid).ifPresent(out::add);
        return out;
    }

    private void event(HubSkill s, Long versionId, HubExecution e, String type) {
        HubUsageEvent u = new HubUsageEvent();
        u.skillId = s.id; u.versionId = versionId; u.agentId = e.agentId; u.actor = e.requester; u.type = type; u.executionId = e.id;
        u.counted = !e.simulated && !s.sample && !s.owner.equals(e.requester);
        u.occurredAt = Instant.now();
        try { usage.save(u); } catch (Exception dup) { log.debug("중복 사용 이벤트 무시: {} {}", e.id, type); }
    }

    private ExecutionView view(HubExecution e) {
        String name = e.skillId != null ? skills.findById(e.skillId).map(s -> s.name).orElse("?") : agentService.agent(e.agentId).name;
        return new ExecutionView(e.id, e.targetType, e.skillId, e.agentId, name, e.requester, e.input, e.status, e.runtimeKind, e.simulated, e.counted,
                e.result, e.error, e.requestedAt, e.completedAt);
    }

    private static String sha(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    @PreDestroy
    void shutdown() { exec.shutdownNow(); }
}
