package com.cenoffice.hub;

import jakarta.persistence.*;
import java.time.Instant;

/** AI HUB 데이터 홀더(테이블 hub_categories). 스키마는 db/migration/V2__ai_hub.sql 이 관리한다. */
@Entity
@Table(name = "hub_categories")
public class HubCategory {
    @Id
    public String code;
    public String name;
    public int sortOrder;

    public HubCategory() {}
}
