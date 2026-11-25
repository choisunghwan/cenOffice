# 예시: 문자열로 만든 SQL

## 입력
```diff
+ public User find(String id) {
+     String sql = "select * from users where id = '" + id + "'";
+     return jdbc.queryForObject(sql, mapper);
+ }
```

## 결과
**다시 검토 필요** — 입력값이 쿼리에 그대로 붙어 SQL 인젝션이 가능합니다.

| 위치 | 심각도 | 문제 | 이유 | 고치는 방법 |
|---|---|---|---|---|
| UserDao.find | 높음 | SQL 인젝션 | id 에 `' or '1'='1` 을 넣으면 전체 조회 | 자리표시자(?)로 바인딩 |
| UserDao.find | 중간 | 없는 id 에서 예외 | queryForObject 는 결과가 없으면 예외 | 결과 없음 처리 추가 |

```java
String sql = "select * from users where id = ?";
return jdbc.query(sql, mapper, id).stream().findFirst().orElse(null);
```

잘된 점: 조회 로직이 한 곳에 모여 있어 고치기 쉽습니다.
