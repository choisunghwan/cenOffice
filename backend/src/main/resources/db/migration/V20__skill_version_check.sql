-- 스킬 검증 결과: 승인 요청 때 자동으로 점검(규칙 + AI 검토)하고, 관리자가 승인 화면에서 본다.
ALTER TABLE hub_skill_versions ADD COLUMN check_verdict VARCHAR(16);
ALTER TABLE hub_skill_versions ADD COLUMN check_report VARCHAR(20000);
ALTER TABLE hub_skill_versions ADD COLUMN checked_at TIMESTAMP WITH TIME ZONE;
