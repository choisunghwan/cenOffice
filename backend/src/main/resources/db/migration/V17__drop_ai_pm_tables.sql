-- 없앤 프로젝트·요구사항·AI PM 기능의 테이블. 삭제 전에 운영 DB 에서 모두 비어 있고(0건) 다른 테이블이 참조하지 않음을 확인했다.
-- 새 DB 에는 원래 없으므로 IF EXISTS 로 건너뛴다.
DROP TABLE IF EXISTS agent_executions;
DROP TABLE IF EXISTS approval_requests;
DROP TABLE IF EXISTS ai_tasks;
DROP TABLE IF EXISTS project_tasks;
DROP TABLE IF EXISTS requirements;
DROP TABLE IF EXISTS projects;
