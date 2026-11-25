CREATE TABLE meeting_projects (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    owner_username VARCHAR(64) NOT NULL,
    skill_id BIGINT REFERENCES hub_skills(id),
    version_id BIGINT REFERENCES hub_skill_versions(id),
    CHECK ((skill_id IS NULL AND version_id IS NULL) OR (skill_id IS NOT NULL AND version_id IS NOT NULL))
);
CREATE TABLE meeting_project_members (
    project_id VARCHAR(36) NOT NULL REFERENCES meeting_projects(id),
    username VARCHAR(64) NOT NULL,
    PRIMARY KEY (project_id, username)
);
CREATE INDEX ix_meeting_project_member_user ON meeting_project_members(username);
ALTER TABLE collaboration_meetings ADD COLUMN project_id VARCHAR(36) REFERENCES meeting_projects(id);
ALTER TABLE meeting_minutes ADD COLUMN project_id VARCHAR(36) REFERENCES meeting_projects(id);
ALTER TABLE meeting_minutes ADD COLUMN skill_version_used VARCHAR(120);
ALTER TABLE hub_skills ADD COLUMN origin_project_id VARCHAR(36) REFERENCES meeting_projects(id);
