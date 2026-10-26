-- Skill 화면 표시 이름(한글 등). 내부 식별 이름(name)은 영문 소문자·숫자·하이픈을 유지한다.
ALTER TABLE hub_skills ADD COLUMN IF NOT EXISTS title VARCHAR(100);
