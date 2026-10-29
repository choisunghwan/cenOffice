package com.cenoffice.hub.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** AI HUB 데이터 홀더(테이블 hub_skill_reviews). 스키마는 db/migration/V2__ai_hub.sql 이 관리한다. */
@Entity
@Table(name = "hub_skill_reviews")
public class HubSkillReview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long skillId;
    public String reviewer;
    public int rating;
    @Column(length = 500)
    public String comment;
    public Instant createdAt;
    public Instant updatedAt;

    public HubSkillReview() {}
}
