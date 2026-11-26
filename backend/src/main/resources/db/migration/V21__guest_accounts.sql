-- 방문자(체험) 계정: 로그인 화면의 '바로 체험하기'로 만들어지는 임시 계정. 며칠 뒤 자동으로 지운다.
ALTER TABLE users ADD COLUMN guest BOOLEAN;
ALTER TABLE users ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
