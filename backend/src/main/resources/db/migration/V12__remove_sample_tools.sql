-- 동작하지 않는 샘플 도구(error-detective, requirement-analyst, 샘플 Agent)를 정리한다.
-- 샘플은 sample=true 로만 구분되므로, 실제 등록 도구는 건드리지 않는다.
DELETE FROM hub_usage_events WHERE skill_id IN (SELECT id FROM hub_skills WHERE sample = TRUE);
DELETE FROM hub_executions WHERE skill_id IN (SELECT id FROM hub_skills WHERE sample = TRUE) OR agent_id IN (SELECT id FROM hub_agents WHERE sample = TRUE);
DELETE FROM hub_approval_history WHERE (target_type = 'SKILL' AND target_id IN (SELECT id FROM hub_skills WHERE sample = TRUE))
                                    OR (target_type = 'AGENT' AND target_id IN (SELECT id FROM hub_agents WHERE sample = TRUE));
DELETE FROM hub_agent_skills WHERE agent_id IN (SELECT id FROM hub_agents WHERE sample = TRUE) OR skill_id IN (SELECT id FROM hub_skills WHERE sample = TRUE);
DELETE FROM hub_agent_versions WHERE agent_id IN (SELECT id FROM hub_agents WHERE sample = TRUE);
DELETE FROM hub_agents WHERE sample = TRUE;
DELETE FROM hub_skill_templates WHERE skill_id IN (SELECT id FROM hub_skills WHERE sample = TRUE);
DELETE FROM hub_skill_reviews WHERE skill_id IN (SELECT id FROM hub_skills WHERE sample = TRUE);
DELETE FROM hub_skill_tags WHERE skill_id IN (SELECT id FROM hub_skills WHERE sample = TRUE);
UPDATE hub_skills SET published_version_id = NULL WHERE sample = TRUE;
DELETE FROM hub_skill_versions WHERE skill_id IN (SELECT id FROM hub_skills WHERE sample = TRUE);
DELETE FROM hub_skills WHERE sample = TRUE;
