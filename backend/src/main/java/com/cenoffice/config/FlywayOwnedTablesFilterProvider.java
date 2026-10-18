package com.cenoffice.config;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;

/**
 * Flyway 가 관리하는 테이블(hub_*)은 Hibernate 의 ddl-auto=update 대상에서 제외한다.
 * 제외하지 않으면 H2 에서 Hibernate 가 컬럼 타입을 기본값(예: VARBINARY(255))으로 되돌려 마이그레이션 스키마를 훼손한다.
 */
public class FlywayOwnedTablesFilterProvider implements SchemaFilterProvider {
    private static final SchemaFilter FILTER = new SchemaFilter() {
        @Override public boolean includeNamespace(Namespace namespace) { return true; }

        @Override public boolean includeTable(Table table) {
            String n = table.getName();
            return n == null || !n.toLowerCase().startsWith("hub_");
        }

        @Override public boolean includeSequence(Sequence sequence) { return true; }
    };

    @Override public SchemaFilter getCreateFilter() { return FILTER; }

    @Override public SchemaFilter getDropFilter() { return FILTER; }

    @Override public SchemaFilter getTruncatorFilter() { return FILTER; }

    @Override public SchemaFilter getMigrateFilter() { return FILTER; }

    @Override public SchemaFilter getValidateFilter() { return FILTER; }
}
