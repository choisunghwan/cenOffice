package com.cenoffice.hub;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "hub_agent_skills")
@IdClass(HubAgentSkill.Pk.class)
public class HubAgentSkill {
    public static class Pk implements Serializable {
        public Long agentId;
        public Long skillId;
        public Pk() {}
        public Pk(Long agentId, Long skillId) { this.agentId = agentId; this.skillId = skillId; }
        @Override public boolean equals(Object o) { return o instanceof Pk p && Objects.equals(agentId, p.agentId) && Objects.equals(skillId, p.skillId); }
        @Override public int hashCode() { return Objects.hash(agentId, skillId); }
    }

    @Id public Long agentId;
    @Id public Long skillId;
    public int sortOrder;

    public HubAgentSkill() {}
    public HubAgentSkill(Long agentId, Long skillId, int sortOrder) { this.agentId = agentId; this.skillId = skillId; this.sortOrder = sortOrder; }
}
