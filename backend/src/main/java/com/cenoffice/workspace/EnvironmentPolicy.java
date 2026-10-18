package com.cenoffice.workspace;

import com.cenoffice.workspace.WsEnums.AiTaskType;
import com.cenoffice.workspace.WsEnums.Capability;
import com.cenoffice.workspace.WsEnums.Environment;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 환경별 AI 작업 허용 범위 (서버에서만 판단 — AI 응답이나 클라이언트 값은 신뢰하지 않는다).
 *  DEV : 읽기 / 테스트 / 개발
 *  STG : 읽기 / 테스트 (제한된 검증)
 *  PROD: 읽기 전용 상태 조회
 *  DEPLOY 는 어떤 환경에서도 허용하지 않는다. 운영 배포는 별도 CI/CD + 운영 담당자 승인 절차로 분리한다.
 */
@Component
public class EnvironmentPolicy {
    public boolean allows(Environment env, Capability cap) {
        if (cap == Capability.DEPLOY) return false;
        Set<Capability> allowed = switch (env) {
            case DEV -> Set.of(Capability.READ, Capability.TEST, Capability.DEVELOP);
            case STG -> Set.of(Capability.READ, Capability.TEST);
            case PROD -> Set.of(Capability.READ);
        };
        return allowed.contains(cap);
    }

    public boolean allows(Environment env, AiTaskType type) { return allows(env, type.capability()); }

    /** 에이전트에 부여할 수 있는 환경. PROD 는 에이전트 권한으로 설정할 수 없다. */
    public boolean agentMayBeGrantedEnvironment(Environment env) { return env != Environment.PROD; }
}
