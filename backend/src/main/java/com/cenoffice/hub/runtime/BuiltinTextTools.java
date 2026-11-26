package com.cenoffice.hub.runtime;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 내장 텍스트 스킬(오류 로그 분석 / SQL 점검 / 회의록 정리 / 주간 보고 정리 / 테스트 케이스 초안).
 * 모두 규칙 기반 로컬 처리다: LLM 이나 외부 호출이 없고, 결과는 "검토용 초안"이며 확정 판단이 아니다.
 * 입력은 신뢰하지 않는 텍스트로 취급하며 어떤 것도 실행하지 않고 출력 표의 구분자만 안전하게 치환한다.
 */
final class BuiltinTextTools {
    private BuiltinTextTools() {}

    private static String cell(String s) {
        String t = s == null ? "" : s.replace('|', '／').replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    private static List<String> lines(String input) {
        return input.lines().map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private static String firstNonNull(String... v) {
        for (String x : v) if (x != null) return x;
        return "미지정";
    }

    private static String stripBullet(String s) { return s.replaceFirst("^(?:[-*•·▪▶◦○●■□]+|\\d{1,2}[.)])\\s*", "").trim(); }

    // ───────────────────────── 1. 오류 로그 분석 ─────────────────────────

    private static final Pattern EXC = Pattern.compile("([A-Za-z_][\\w.$]*(?:Exception|Error))(?::\\s*(.*))?");
    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+([\\w.$<>]+)\\.([\\w$<>]+)\\(([^)]*)\\)");
    private static final Pattern TS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}");
    private static final List<String> FRAMEWORK = List.of("java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "org.springframework.", "org.hibernate.",
            "org.apache.", "io.netty.", "reactor.", "org.eclipse.", "com.zaxxer.", "org.postgresql.", "oracle.", "com.mysql.", "org.h2.", "org.junit.", "com.fasterxml.");
    private static final LinkedHashMap<String, String> HINTS = new LinkedHashMap<>();
    static {
        HINTS.put("NullPointerException", "null 참조입니다. 스택 최상단의 애플리케이션 프레임에서 해당 줄의 객체·반환값·Optional 이 null 일 수 있는지 확인하세요.");
        HINTS.put("LazyInitializationException", "트랜잭션(세션) 밖에서 지연 로딩 연관을 읽었습니다. 서비스 계층 트랜잭션 안에서 DTO 로 변환하거나 fetch join 을 사용하세요.");
        HINTS.put("DataIntegrityViolationException", "제약조건 위반(중복 키, NOT NULL, FK)입니다. 아래 원인 메시지의 제약조건 이름과 저장하려던 값을 확인하세요.");
        HINTS.put("ConstraintViolationException", "제약조건 또는 Bean Validation 위반입니다. 위반된 필드와 값을 확인하세요.");
        HINTS.put("BadSqlGrammarException", "SQL 문법/컬럼·테이블 이름 오류입니다. 실제 실행된 SQL 과 스키마(마이그레이션 적용 여부)를 확인하세요.");
        HINTS.put("SQLSyntaxErrorException", "SQL 문법 또는 존재하지 않는 객체 참조입니다. DB 종류별 문법 차이와 대소문자/예약어를 확인하세요.");
        HINTS.put("ConnectException", "대상 서버에 연결하지 못했습니다. 주소·포트·방화벽·대상 서비스 기동 여부를 확인하세요.");
        HINTS.put("UnknownHostException", "호스트 이름을 해석하지 못했습니다. DNS/호스트 설정과 오타를 확인하세요.");
        HINTS.put("SocketTimeoutException", "응답 시간 초과입니다. 대상 서비스 지연, 타임아웃 설정, 네트워크 상태를 확인하세요.");
        HINTS.put("ConnectTimeoutException", "연결 시간 초과입니다. 네트워크 경로와 대상 서비스 상태를 확인하세요.");
        HINTS.put("CannotCreateTransactionException", "DB 연결을 얻지 못했습니다. DB 기동 여부, 커넥션 풀 고갈, 접속 정보를 확인하세요.");
        HINTS.put("OutOfMemoryError", "메모리 부족입니다. 힙 크기, 대량 조회/컬렉션 누적, 메모리 누수 가능성을 확인하세요(힙 덤프 분석 권장).");
        HINTS.put("StackOverflowError", "재귀 호출이 끝나지 않았을 가능성이 큽니다. 순환 참조(JSON 직렬화, toString, 양방향 연관)를 확인하세요.");
        HINTS.put("ClassNotFoundException", "클래스를 찾지 못했습니다. 의존성 누락, 패키징, 클래스패스를 확인하세요.");
        HINTS.put("NoClassDefFoundError", "실행 시점에 클래스 정의가 없습니다. 의존성 버전 충돌이나 초기화 실패(앞선 ExceptionInInitializerError)를 확인하세요.");
        HINTS.put("NoSuchMethodError", "라이브러리 버전 불일치 가능성이 큽니다. 의존성 트리에서 같은 라이브러리의 중복 버전을 확인하세요.");
        HINTS.put("UnsatisfiedDependencyException", "주입할 Bean 을 만들지 못했습니다. 하위 원인(Caused by)의 누락된 Bean/설정 값/순환 의존을 확인하세요.");
        HINTS.put("BeanCreationException", "Bean 생성 실패입니다. 가장 안쪽 Caused by 가 실제 원인입니다.");
        HINTS.put("HttpMessageNotReadableException", "요청 본문(JSON) 형식 또는 타입이 맞지 않습니다. 요청 예시와 DTO 필드 타입을 비교하세요.");
        HINTS.put("MethodArgumentNotValidException", "입력값 검증 실패입니다. 위반된 필드와 검증 어노테이션을 확인하세요.");
        HINTS.put("AccessDeniedException", "권한 부족입니다. 사용자 역할과 접근 규칙(보안 설정)을 확인하세요.");
        HINTS.put("JsonParseException", "JSON 문법 오류입니다. 요청/응답 원문을 확인하세요.");
        HINTS.put("DeadlockLoserDataAccessException", "DB 교착 상태입니다. 트랜잭션 범위와 자원 접근 순서를 점검하고 재시도 정책을 검토하세요.");
        HINTS.put("LockTimeoutException", "잠금 대기 시간 초과입니다. 오래 열린 트랜잭션과 잠금 경합을 확인하세요.");
        HINTS.put("IllegalArgumentException", "잘못된 인자입니다. 예외 메시지의 값과 호출부 입력 검증을 확인하세요.");
        HINTS.put("IllegalStateException", "객체/설정이 기대하지 않은 상태입니다. 예외 메시지와 초기화 순서를 확인하세요.");
        HINTS.put("PSQLException", "PostgreSQL 오류입니다. SQLState 와 메시지(제약조건/문법/권한)를 확인하세요.");
    }

    static String logAnalysis(String input) {
        List<String> all = input.lines().toList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String[]> chain = new ArrayList<>(); // [type, message]
        LinkedHashSet<String> appFrames = new LinkedHashSet<>();
        int errors = 0, warns = 0;
        String first = null, last = null;
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        Pattern code = Pattern.compile("(ORA-\\d{5}|SQLState[:=]\\s*\\w+|SQL Error:\\s*-?\\d+|ERROR:\\s+[^\\n]{3,80}|Duplicate entry[^\\n]{0,60})");
        for (String raw : all) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher t = TS.matcher(line);
            if (t.find()) { if (first == null) first = t.group(); last = t.group(); }
            if (line.matches(".*\\b(ERROR|SEVERE|FATAL)\\b.*")) errors++;
            else if (line.matches(".*\\b(WARN|WARNING)\\b.*")) warns++;
            Matcher fm = FRAME.matcher(raw);
            if (fm.find()) {
                String cls = fm.group(1);
                if (FRAMEWORK.stream().noneMatch(cls::startsWith)) appFrames.add(cls + "." + fm.group(2) + " (" + fm.group(3) + ")");
                continue;
            }
            Matcher cm = code.matcher(line);
            while (cm.find() && codes.size() < 8) codes.add(cm.group().trim());
            Matcher em = EXC.matcher(line.startsWith("Caused by:") ? line.substring(10).trim() : line);
            if (em.find() && !line.startsWith("...")) {
                String type = em.group(1);
                counts.merge(type, 1, Integer::sum);
                if (chain.isEmpty() || line.startsWith("Caused by:") || !chain.get(chain.size() - 1)[0].equals(type)) chain.add(new String[]{type, em.group(2) == null ? "" : em.group(2)});
            }
        }
        StringBuilder o = new StringBuilder("# 오류 로그 분석 결과\n\n규칙 기반 분석입니다. 원인을 확정하는 것이 아니라 확인 순서를 정리한 초안입니다.\n\n");
        if (chain.isEmpty()) {
            o.append("예외(Exception/Error) 형태의 줄을 찾지 못했습니다.\n\n- 스택 트레이스 전체(`Caused by` 포함)를 붙여 넣었는지 확인하세요.\n- 로그에 개인정보·비밀번호·토큰이 있으면 마스킹한 뒤 사용하세요.\n");
            return o.toString();
        }
        String[] top = chain.get(0), root = chain.get(chain.size() - 1);
        o.append("## 요약\n| 항목 | 내용 |\n|---|---|\n| 최상위 예외 | ").append(cell(top[0])).append(" |\n| 메시지 | ").append(cell(top[1].isEmpty() ? "(없음)" : top[1])).append(" |\n");
        o.append("| 근본 원인 후보 (가장 안쪽 Caused by) | ").append(cell(root[0])).append(" |\n| 근본 원인 메시지 | ").append(cell(root[1].isEmpty() ? "(없음)" : root[1])).append(" |\n");
        o.append("| ERROR / WARN 줄 수 | ").append(errors).append(" / ").append(warns).append(" |\n");
        if (first != null) o.append("| 로그 시각 범위 | ").append(first).append(" ~ ").append(last).append(" |\n");
        o.append("\n## 예외 발생 횟수\n| 예외 | 횟수 |\n|---|---|\n");
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(10).forEach((e) -> o.append("| ").append(cell(e.getKey())).append(" | ").append(e.getValue()).append(" |\n"));
        o.append("\n## 예외 연쇄 (바깥 → 안쪽)\n");
        for (int i = 0; i < Math.min(chain.size(), 8); i++) o.append(i + 1).append(". ").append(cell(chain.get(i)[0])).append(chain.get(i)[1].isEmpty() ? "" : " — " + cell(chain.get(i)[1])).append('\n');
        o.append("\n## 애플리케이션 코드 후보 (프레임워크 프레임 제외)\n");
        if (appFrames.isEmpty()) o.append("- 프레임워크/JDK 가 아닌 프레임을 찾지 못했습니다. 패키지 이름 기준으로 직접 확인하세요.\n");
        else appFrames.stream().limit(6).forEach((f) -> o.append("- ").append(cell(f)).append('\n'));
        if (!codes.isEmpty()) { o.append("\n## DB/SQL 오류 코드\n"); codes.forEach((c) -> o.append("- ").append(cell(c)).append('\n')); }
        o.append("\n## 원인 후보와 확인 순서\n");
        int n = 0;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String[]> order = new ArrayList<>(chain);
        Collections.reverse(order); // 안쪽(근본) 원인부터
        for (String[] c : order) {
            String simple = c[0].substring(c[0].lastIndexOf('.') + 1);
            for (var h : HINTS.entrySet()) {
                if (simple.endsWith(h.getKey()) && seen.add(h.getKey()) && n < 5) o.append(++n).append(". ").append(simple).append(" — ").append(h.getValue()).append('\n');
            }
        }
        if (n == 0) o.append("1. 등록된 규칙에 없는 예외입니다. 가장 안쪽 `Caused by` 메시지와 위 애플리케이션 코드 후보의 줄 번호부터 확인하세요.\n");
        o.append("\n## 다음 단계\n- 같은 오류가 반복되는지(발생 횟수·시각)와 최근 배포/설정/데이터 변경 시점을 대조하세요.\n- 재현 조건(요청 값, 사용자, 데이터)을 기록하고 수정 후 같은 조건으로 확인하세요.\n");
        return o.toString();
    }

    // ───────────────────────── 2. SQL 점검 ─────────────────────────

    private record Finding(String level, String rule, String detail, String fix) {}

    static String sqlReview(String input) {
        List<String> statements = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quote = false;
        for (char c : input.toCharArray()) {
            if (c == '\'') quote = !quote;
            if (c == ';' && !quote) { if (!cur.toString().isBlank()) statements.add(cur.toString().trim()); cur.setLength(0); }
            else cur.append(c);
        }
        if (!cur.toString().isBlank()) statements.add(cur.toString().trim());
        StringBuilder o = new StringBuilder("# SQL 점검 결과\n\n정적 규칙으로 문장만 점검했습니다. 데이터 양·인덱스·실행 계획은 반영되지 않았으므로 최종 판단은 DBA/개발자 검토와 실행 계획 확인이 필요합니다.\n\n");
        int high = 0, mid = 0, low = 0, idx = 0;
        StringBuilder body = new StringBuilder();
        for (String st : statements.stream().limit(30).toList()) {
            idx++;
            String s = st.replaceAll("--[^\\n]*", " ").replaceAll("/\\*.*?\\*/", " ").replaceAll("\\s+", " ").trim();
            String u = s.toUpperCase(Locale.ROOT);
            List<Finding> f = new ArrayList<>();
            boolean where = u.contains(" WHERE ");
            if (u.startsWith("UPDATE") && !where) f.add(new Finding("높음", "WHERE 없는 UPDATE", "모든 행이 변경됩니다.", "조건을 추가하거나 의도한 전체 변경인지 확인하고, 실행 전 백업/트랜잭션을 준비하세요."));
            if (u.startsWith("DELETE") && !where) f.add(new Finding("높음", "WHERE 없는 DELETE", "모든 행이 삭제됩니다.", "조건을 추가하거나 TRUNCATE/백업 여부를 확인하세요. 운영 DB 는 승인 후 실행하세요."));
            if (u.matches("^(DROP|TRUNCATE|ALTER)\\b.*")) f.add(new Finding("높음", "구조 변경/삭제 구문(" + u.split(" ")[0] + ")", "되돌리기 어려운 변경입니다.", "마이그레이션 스킬로 관리하고, 운영 적용 전 백업과 승인을 받으세요."));
            if (u.matches(".*\\bSELECT\\s+(DISTINCT\\s+)?\\*.*")) f.add(new Finding("중간", "SELECT *", "불필요한 컬럼까지 조회해 네트워크·메모리 비용이 늘고 컬럼 변경에 취약합니다.", "필요한 컬럼만 명시하세요."));
            if (s.matches("(?is).*\\bLIKE\\s+'%.*")) f.add(new Finding("중간", "앞부분 와일드카드 LIKE", "LIKE '%값' 은 일반 인덱스를 사용하지 못해 전체 스캔이 될 수 있습니다.", "접두 검색(LIKE '값%')으로 바꾸거나 전문 검색/별도 인덱스를 검토하세요."));
            if (u.matches(".*\\bWHERE\\b.*\\b(UPPER|LOWER|TRUNC|TO_CHAR|SUBSTR|SUBSTRING|NVL|COALESCE|DATE|CAST)\\s*\\(\\s*[A-Z_][A-Z0-9_.]*\\s*[,)].*"))
                f.add(new Finding("중간", "WHERE 절에서 컬럼 가공", "컬럼에 함수를 적용하면 해당 컬럼 인덱스를 못 쓸 수 있습니다.", "상수 쪽을 가공하거나 범위 조건(>=, <)으로 바꾸고, 필요하면 함수 기반 인덱스를 검토하세요."));
            if (u.matches(".*\\bNOT\\s+IN\\s*\\(\\s*SELECT\\b.*")) f.add(new Finding("중간", "NOT IN (서브쿼리)", "서브쿼리에 NULL 이 있으면 결과가 비고 성능도 불리할 수 있습니다.", "NOT EXISTS 또는 LEFT JOIN … IS NULL 로 바꾸는 것을 검토하세요."));
            if (u.matches(".*\\bFROM\\s+[A-Z_][\\w.]*(\\s+(AS\\s+)?[A-Z_]\\w*)?\\s*,\\s*[A-Z_][\\w.]*.*") && !u.matches(".*\\bWHERE\\b.*=.*"))
                f.add(new Finding("높음", "조인 조건이 없는 콤마 조인", "카티전 곱으로 결과가 폭증할 수 있습니다.", "명시적 JOIN … ON 으로 조건을 지정하세요."));
            if (u.matches("^SELECT\\b.*") && !where && !u.matches(".*\\b(LIMIT|FETCH\\s+FIRST|ROWNUM|TOP)\\b.*") && !u.matches(".*\\b(COUNT|SUM|AVG|MAX|MIN)\\s*\\(.*") && !u.contains(" GROUP BY "))
                f.add(new Finding("낮음", "조건·건수 제한 없는 SELECT", "테이블이 커지면 전체를 읽어 응답이 느려질 수 있습니다.", "WHERE 조건이나 LIMIT/페이징을 추가하세요."));
            Matcher in = Pattern.compile("(?i)\\bIN\\s*\\(([^()]*)\\)").matcher(s);
            while (in.find()) if (in.group(1).split(",").length > 100) { f.add(new Finding("중간", "IN 목록이 매우 김(100개 초과)", "파싱·실행 계획 비용이 커집니다.", "임시 테이블/조인이나 배치 분할을 검토하세요.")); break; }
            if (input.matches("(?s).*[\"']\\s*\\+\\s*[A-Za-z_].*") ) f.add(new Finding("높음", "문자열 결합으로 만든 SQL 의심", "사용자 입력이 결합되면 SQL Injection 위험이 있습니다.", "PreparedStatement 의 바인딩 파라미터(?, :name)를 사용하세요."));
            for (Finding x : f) { switch (x.level()) { case "높음" -> high++; case "중간" -> mid++; default -> low++; } }
            body.append("### ").append(idx).append(". ").append(cell(s.length() > 90 ? s.substring(0, 90) + "…" : s)).append('\n');
            if (f.isEmpty()) body.append("자동 점검 규칙에 걸린 항목이 없습니다.\n\n");
            else {
                body.append("| 등급 | 점검 항목 | 설명 | 개선 제안 |\n|---|---|---|---|\n");
                for (Finding x : f) body.append("| ").append(x.level()).append(" | ").append(cell(x.rule())).append(" | ").append(cell(x.detail())).append(" | ").append(cell(x.fix())).append(" |\n");
                body.append('\n');
            }
        }
        if (statements.isEmpty()) return o.append("점검할 SQL 문장을 찾지 못했습니다. `;` 로 구분된 SQL 을 붙여 넣어 주세요.\n").toString();
        o.append("## 요약\n문장 ").append(Math.min(statements.size(), 30)).append("개 점검 · 높음 ").append(high).append(" · 중간 ").append(mid).append(" · 낮음 ").append(low).append("\n\n");
        if (statements.size() > 30) o.append("※ 처음 30개 문장만 점검했습니다.\n\n");
        return o.append("## 문장별 결과\n").append(body).append("## 참고\n- 실제 데이터 양과 인덱스에 따라 같은 SQL 도 결과가 달라집니다. 운영 DB 에 실행하기 전 실행 계획(EXPLAIN)을 확인하세요.\n").toString();
    }

    // ───────────────────────── 3. 회의록 정리 ─────────────────────────

    private static final Pattern DUE = Pattern.compile("(\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|\\d{1,2}/\\d{1,2}|\\d{1,2}월\\s*\\d{1,2}일|(?:다음|이번)\\s*주\\s*[월화수목금]?요?일?|금주|차주|내일|오늘|[월화수목금]요일까지|월말|말일)");
    private static final Pattern OWNER = Pattern.compile("(?:담당|owner|Owner)\\s*[:：]?\\s*([가-힣A-Za-z]{2,10})|@([가-힣A-Za-z0-9_]{2,15})|^([가-힣]{1,3}(?:대리|과장|차장|부장|사원|팀장|매니저|님))(?=\\s|:|：|$)|^([가-힣]{2,4})\\s*[:：]");

    static String meetingMinutes(String input) {
        List<String> ls = lines(input);
        String title = ls.isEmpty() ? "" : stripBullet(ls.get(0));
        boolean titleLine = title.length() <= 60 && !title.matches(".*(결정|담당|까지|확정).*") && !title.matches("^(참석|일시|일자|안건).*");
        String attendees = "원문에 없음", date = "원문에 없음";
        List<String> decisions = new ArrayList<>(), issues = new ArrayList<>(), notes = new ArrayList<>();
        List<String[]> actions = new ArrayList<>();
        for (int i = 0; i < ls.size(); i++) {
            String line = stripBullet(ls.get(i));
            if (i == 0 && titleLine) continue;
            Matcher at = Pattern.compile("^(?:참석자?|참여자?)\\s*[:：]\\s*(.+)").matcher(line);
            if (at.find()) { attendees = cell(at.group(1)); continue; }
            Matcher dt = Pattern.compile("^(?:일시|일자|날짜|회의\\s*일시)\\s*[:：]\\s*(.+)").matcher(line);
            if (dt.find()) { date = cell(dt.group(1)); continue; }
            if (date.equals("원문에 없음") && i < 4) { Matcher d = Pattern.compile("(\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|\\d{1,2}월\\s*\\d{1,2}일)").matcher(line); if (d.find() && line.length() < 40) { date = d.group(); continue; } }
            Matcher own = OWNER.matcher(line), due = DUE.matcher(line);
            boolean hasOwner = own.find(), hasDue = due.find();
            if (line.matches(".*(결정|확정|합의|승인|채택|하기로\\s*(했|함|한)).*")) decisions.add(line);
            else if (hasOwner || line.matches(".*(액션|TODO|todo|Action|해야\\s*함|예정|요청\\s*드|공유\\s*필요|작성\\s*필요|검토\\s*필요).*") || (hasDue && line.matches(".*(까지|전까지).*"))) {
                String who = hasOwner ? firstNonNull(own.group(1), own.group(2), own.group(3), own.group(4)) : "미지정";
                actions.add(new String[]{line, who, hasDue ? due.group(1) : "미지정"});
            } else if (line.matches(".*([?？]|이슈|문제|리스크|위험|우려|지연|확인\\s*필요|미정|논의\\s*필요).*")) issues.add(line);
            else notes.add(line);
        }
        StringBuilder o = new StringBuilder("# 회의록 정리 초안\n\n규칙 기반으로 원문을 분류한 결과입니다. 분류가 틀릴 수 있으니 공유 전에 검토하세요. 원문에 없는 내용은 만들지 않았습니다.\n\n");
        o.append("| 항목 | 내용 |\n|---|---|\n| 회의 제목 | ").append(titleLine ? cell(title) : "원문에 없음").append(" |\n| 일시 | ").append(cell(date)).append(" |\n| 참석자 | ").append(attendees).append(" |\n\n");
        o.append("## 결정 사항\n");
        if (decisions.isEmpty()) o.append("- 결정 표현('결정/확정/합의')이 있는 줄을 찾지 못했습니다.\n"); else decisions.forEach((d) -> o.append("- ").append(cell(d)).append('\n'));
        o.append("\n## 액션 아이템\n");
        if (actions.isEmpty()) o.append("- 담당·기한이 드러난 항목을 찾지 못했습니다.\n");
        else {
            o.append("| No | 내용 | 담당 | 기한(원문 표현) |\n|---|---|---|---|\n");
            int n = 0;
            for (String[] a : actions) o.append("| ").append(++n).append(" | ").append(cell(a[0])).append(" | ").append(cell(a[1])).append(" | ").append(cell(a[2])).append(" |\n");
            long missing = actions.stream().filter((a) -> a[1].equals("미지정") || a[2].equals("미지정")).count();
            if (missing > 0) o.append("\n※ 담당 또는 기한이 '미지정'인 항목 ").append(missing).append("건은 회의 후 확정이 필요합니다. 기한은 원문 표현 그대로이며 날짜로 변환하지 않았습니다.\n");
        }
        o.append("\n## 이슈 · 확인 필요\n");
        if (issues.isEmpty()) o.append("- 없음\n"); else issues.forEach((d) -> o.append("- ").append(cell(d)).append('\n'));
        o.append("\n## 논의 내용\n");
        if (notes.isEmpty()) o.append("- 없음\n"); else notes.stream().limit(40).forEach((d) -> o.append("- ").append(cell(d)).append('\n'));
        return o.toString();
    }
}
