package com.cenoffice.hub.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "hub_skill_tags")
@IdClass(HubSkillTag.Pk.class)
public class HubSkillTag {
    public static class Pk implements Serializable {
        public Long skillId;
        public String tag;
        public Pk() {}
        public Pk(Long skillId, String tag) { this.skillId = skillId; this.tag = tag; }
        @Override public boolean equals(Object o) { return o instanceof Pk p && Objects.equals(skillId, p.skillId) && Objects.equals(tag, p.tag); }
        @Override public int hashCode() { return Objects.hash(skillId, tag); }
    }

    @Id public Long skillId;
    @Id public String tag;

    public HubSkillTag() {}
    public HubSkillTag(Long skillId, String tag) { this.skillId = skillId; this.tag = tag; }
}
