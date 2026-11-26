package com.cenoffice.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 체험용 공용 계정(예: 발표장에서 여러 사람이 같은 아이디로 들어와 써 보는 계정).
 * 이 계정들은 '사용자당' 속도 제한과 같은 입력 중복 차단을 넉넉하게 푼다. 여러 사람이 한 계정을 나눠 쓰기 때문이다.
 * 권한은 바꾸지 않는다(일반 사용자 권한 그대로). 비우면(app.demo.accounts=) 아무 계정도 예외가 아니다.
 */
@Component
public class DemoAccounts {
    /** 체험 계정의 분당 실행·대화 한도(계정 전체 합계) */
    public static final int PER_MINUTE = 600;

    private final Set<String> names;

    public DemoAccounts(@Value("${app.demo.accounts:demo}") String accounts) {
        this.names = Arrays.stream(accounts.split(","))
                .map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isDemo(String username) {
        return username != null && names.contains(username);
    }

    /** 체험 계정이면 넉넉한 한도, 아니면 원래 한도 */
    public int limit(String username, int normal) {
        return isDemo(username) ? Math.max(normal, PER_MINUTE) : normal;
    }
}
