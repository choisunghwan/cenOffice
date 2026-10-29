-- AI PM 기능을 없애면서 의미가 사라진 열을 지운다. (role: PM/DEV/QA 역할, allowed_environments: DEV/STG/PROD 작업 환경)
-- PostgreSQL 에서는 role 열의 CHECK 제약도 열과 함께 사라진다.
ALTER TABLE ai_agents DROP COLUMN role;
ALTER TABLE ai_agents DROP COLUMN allowed_environments;
