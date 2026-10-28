-- 결과물 양식을 형식(hwpx/docx/xlsx/pptx)별로 하나씩 둘 수 있게 기본 키를 (skill_id, format) 으로 바꾼다.
CREATE TABLE hub_skill_templates_v2 (
    skill_id    BIGINT       NOT NULL,
    format      VARCHAR(8)   NOT NULL,
    file_name   VARCHAR(200) NOT NULL,
    data        BYTEA        NOT NULL,
    fields      VARCHAR(20000) NOT NULL,
    created_by  VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (skill_id, format)
);
INSERT INTO hub_skill_templates_v2 (skill_id, format, file_name, data, fields, created_by, created_at)
    SELECT skill_id, format, file_name, data, fields, created_by, created_at FROM hub_skill_templates;
DROP TABLE hub_skill_templates;
ALTER TABLE hub_skill_templates_v2 RENAME TO hub_skill_templates;
