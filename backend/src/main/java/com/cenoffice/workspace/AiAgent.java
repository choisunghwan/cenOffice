package com.cenoffice.workspace;

import jakarta.persistence.*;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "ai_agents")
public class AiAgent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 32)
    private String code;
    @Column(nullable = false, length = 64)
    private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private WsEnums.AgentRole role;
    private boolean enabled = true;
    /** 에이전트가 작업할 수 있는 환경(쉼표 구분). PROD 는 저장/부여할 수 없다. */
    @Column(nullable = false, length = 32)
    private String allowedEnvironments;

    protected AiAgent() {}

    public AiAgent(String code, String name, WsEnums.AgentRole role, Set<WsEnums.Environment> envs) {
        this.code = code;
        this.name = name;
        this.role = role;
        this.allowedEnvironments = envs.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public WsEnums.AgentRole getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public String getAllowedEnvironments() { return allowedEnvironments; }

    public Set<WsEnums.Environment> environments() {
        Set<WsEnums.Environment> s = EnumSet.noneOf(WsEnums.Environment.class);
        Arrays.stream(allowedEnvironments.split(",")).filter(x -> !x.isBlank()).forEach(x -> s.add(WsEnums.Environment.valueOf(x.trim())));
        return s;
    }
}
