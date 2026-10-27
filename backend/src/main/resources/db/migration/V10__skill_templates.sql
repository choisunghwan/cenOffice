-- 스킬의 결과물 양식(hwpx). 스킬당 1개이며 다시 올리면 교체한다.
-- fields: 양식에서 찾은 채울 자리(key, label, kind) 목록의 JSON.
CREATE TABLE hub_skill_templates (
    skill_id    BIGINT       PRIMARY KEY,
    file_name   VARCHAR(200) NOT NULL,
    format      VARCHAR(8)   NOT NULL,
    data        BYTEA        NOT NULL,
    fields      VARCHAR(20000) NOT NULL,
    created_by  VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
