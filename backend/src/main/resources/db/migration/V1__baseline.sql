-- 기준선(Baseline): 기존 CEN OFFICE 테이블(users, devices, ... )은 Hibernate(ddl-auto=update)가 관리해 왔다.
-- 이 파일은 의도적으로 비어 있다. 기존 DB 는 baseline-on-migrate 로 이 버전을 "적용됨"으로 표시하고,
-- 새 DB 는 이 파일(no-op)을 거쳐 V2 부터 적용한다. 기존 테이블과 데이터는 변경하지 않는다.
SELECT 1;
