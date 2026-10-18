package com.cenoffice.hub;

import jakarta.persistence.*;
import java.time.Instant;

/** AI HUB 데이터 홀더(테이블 hub_skill_versions). 스키마는 db/migration/V2__ai_hub.sql 이 관리한다. */
@Entity
@Table(name = "hub_skill_versions")
public class HubSkillVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long skillId;
    public String version;
    public String status;
    @Column(length = 4000)
    public String frontmatter;
    @Column(length = 100000)
    public String skillMd;
    @Column(length = 4000)
    public String fileList;
    public String packageKind;
    public byte[] packageData;
    public String sha256;
    @Column(length = 2000)
    public String riskFlags;
    public String createdBy;
    public Instant createdAt;

    public HubSkillVersion() {}
}
