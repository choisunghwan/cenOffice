package com.cenoffice.office.agent;

import jakarta.persistence.*;

/** 3D 오피스에서 대화할 수 있는 AI 직원(예: 아리). */
@Entity
@Table(name = "ai_agents")
public class AiAgent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 32)
    private String code;
    @Column(nullable = false, length = 64)
    private String name;
    private boolean enabled = true;

    protected AiAgent() {}

    public AiAgent(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public boolean isEnabled() { return enabled; }

    public void rename(String newName) { this.name = newName; }

    public void disable() { this.enabled = false; }
}
