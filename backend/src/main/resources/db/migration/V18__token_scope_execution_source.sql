-- 토큰 범위(MCP: Claude Code 등 / NAVI: CenNavi 확장)와 실행 출처(WEB / MCP / CENNAVI, 사이트 도메인만).
ALTER TABLE hub_tokens ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'MCP';
ALTER TABLE hub_executions ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'WEB';
ALTER TABLE hub_executions ADD COLUMN source_host VARCHAR(255);
