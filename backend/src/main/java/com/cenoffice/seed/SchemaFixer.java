package com.cenoffice.seed;

import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 임시 스키마 보정(마이그레이션 도구 도입 전). 예전 버전이 만든 ai_tasks.status CHECK 제약이 새 상태(NEEDS_INPUT)를
 * 막으므로 해당 제약만 제거한다. 멱등이며, 제약이 없으면 아무것도 하지 않는다.
 * 운영 DB 에는 Flyway 같은 정식 마이그레이션을 도입해 이 클래스를 대체해야 한다.
 */
@Component
@Order(0)
public class SchemaFixer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(SchemaFixer.class);

    private final JdbcTemplate jdbc;
    private final DataSource ds;

    public SchemaFixer(JdbcTemplate jdbc, DataSource ds) {
        this.jdbc = jdbc;
        this.ds = ds;
    }

    @Override
    public void run(String... args) {
        try {
            String product;
            try (var c = ds.getConnection()) { product = c.getMetaData().getDatabaseProductName(); }
            List<String> names;
            if (product.toLowerCase().contains("h2")) {
                names = jdbc.queryForList(
                        "SELECT cc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc "
                                + "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc ON tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME AND tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA "
                                + "WHERE UPPER(tc.TABLE_NAME) = 'AI_TASKS' AND cc.CHECK_CLAUSE LIKE '%WAITING_APPROVAL%' AND cc.CHECK_CLAUSE NOT LIKE '%NEEDS_INPUT%'",
                        String.class);
            } else if (product.toLowerCase().contains("postgres")) {
                names = jdbc.queryForList(
                        "SELECT conname FROM pg_constraint WHERE conrelid = 'ai_tasks'::regclass AND contype = 'c' "
                                + "AND pg_get_constraintdef(oid) LIKE '%WAITING_APPROVAL%' AND pg_get_constraintdef(oid) NOT LIKE '%NEEDS_INPUT%'",
                        String.class);
            } else {
                return;
            }
            for (String n : names) {
                jdbc.execute("ALTER TABLE ai_tasks DROP CONSTRAINT \"" + n.replace("\"", "") + "\"");
                log.warn("낡은 CHECK 제약 제거: ai_tasks.{}", n);
            }
        } catch (Exception e) {
            log.warn("스키마 보정을 건너뜁니다: {}", e.getMessage());
        }
    }
}
