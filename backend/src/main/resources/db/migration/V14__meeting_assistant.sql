-- Only question/answer text is durable. Synthesized audio stays in process memory.
CREATE TABLE meeting_ai_turns (
    id VARCHAR(36) PRIMARY KEY,
    meeting_id VARCHAR(36) NOT NULL REFERENCES collaboration_meetings(id),
    username VARCHAR(64) NOT NULL,
    question VARCHAR(1200) NOT NULL,
    answer VARCHAR(2000) NOT NULL DEFAULT '',
    state VARCHAR(24) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_meeting_ai_turns ON meeting_ai_turns(meeting_id, created_at);
