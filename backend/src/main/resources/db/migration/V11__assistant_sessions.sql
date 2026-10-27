-- 홈 입력창의 채팅방(대화 세션). 본인 것만 보이며, 대화(turns)는 화면이 그리는 모양 그대로 JSON 배열로 둔다.
CREATE TABLE assistant_sessions (
    id          VARCHAR(36)  PRIMARY KEY,
    owner       VARCHAR(64)  NOT NULL,
    title       VARCHAR(120) NOT NULL,
    turns       TEXT         NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_assistant_sessions_owner ON assistant_sessions(owner, updated_at);
