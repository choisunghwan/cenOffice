# AI HUB 구축 전 분석 (STEP 1~3)

## 1. 현재 프로젝트 구조

| 영역 | 내용 |
|---|---|
| 프론트엔드 | `frontend/` React 19 + TypeScript + Vite + Tailwind 4 + Zustand, 3D 는 React Three Fiber. WORKSPACE 화면은 `src/workspace/*`(kit.tsx 가 공통 UI), 3D 는 `src/game/*`, 실시간/채팅은 `src/multiplayer.ts` |
| 백엔드 | `backend/` Spring Boot 3.3 / Java 21. 패키지: `api`(인증·장비·회의실), `device`, `workspace`(프로젝트/요구사항/AI 작업/승인), `pm`(AI PM, LlmProvider), `chat`, `ws`(WebSocket·접속), `security`, `seed` |
| DB | 로컬 파일 H2(PostgreSQL 호환 모드), `ddl-auto=update`. **마이그레이션 도구 없음**. 사용자 참조는 FK 없이 `username` 문자열 |
| 인증/권한 | JWT(HS256, `role` 클레임: `ADMIN`/`USER`), `@PreAuthorize`, 감사 로그(`AuditService`, 별도 트랜잭션) |
| 3D ↔ 2D | 단일 Zustand 스토어(`mode`: workspace/office). 같은 REST/WS API 사용 |
| AI | `LlmProvider`(Mock/Gemini/Anthropic/OpenAI), AI PM(아리) 흐름, 서버 검증, 승인 |

## 2. 기존 상태 (기준선, 작업 전 실행)

`mvn test` → **33건 모두 통과** (Chat 8, Office 4, PmLlmOutputValidation 5, Pm 5, PresenceWs 1, WebSocketSecurity 2, Workspace 8).
Phase 1~3(3D, 장비, 회의실, WORKSPACE, AI PM, 멀티플레이어/채팅)은 구현·검증 완료 상태. 미완: Development/Testing/Deployment 메뉴, 실제 IoT, 모니터링 실데이터.

## 3. 충돌 위험과 대응

| 위험 | 대응 |
|---|---|
| `users.role` 에 Hibernate 가 만든 CHECK 제약(`ADMIN`,`USER`) → 새 역할 `CREATOR` 추가 시 저장 거부 | **역할을 추가하지 않고 매핑**: `ADMIN`→ADMIN, `USER`→USER이며 자기 리소스를 등록한 USER 가 CREATOR. 등록·본인 리소스 관리 권한은 USER 에게 부여 |
| `@Enumerated` 컬럼의 CHECK 제약이 값 추가 시 막힘(PM 작업에서 이미 겪음) | AI HUB 신규 테이블은 **문자열 컬럼 + 서버 검증**으로 두고 CHECK 제약을 만들지 않음 |
| 마이그레이션 도구 부재 | **Flyway 추가**(요구사항: 마이그레이션 파일 작성). 기존 DB 는 `baseline-on-migrate` 로 V1 기준선 처리, 신규 테이블만 `V2__ai_hub.sql`. 기존 테이블/데이터는 변경하지 않음(비파괴) |
| 신규 테이블이 `users` FK 를 요구하면 새 DB 에서 생성 순서 문제 | 기존 관례대로 사용자 참조는 `username` 문자열(FK 없음) |
| 3D 변경으로 카메라/충돌/동기화 영향 | 3D 변경은 벽 부착형 터미널 1개(충돌 없음) + 상호작용 항목 1개로 한정 |
| 기존 AI PM/승인 로직과 중복 | AI HUB 는 `com.cenoffice.hub` 독립 패키지, 테이블 접두사 `hub_`. 기존 `AiAgent`(PM 흐름용 고정 3종)와 별개로 `HubAgent` 를 둠 |

## 4. 설계 결정 (보안·정책 — 보수적 기본값, 변경 가능)

1. **실행 가능한 파일은 업로드 거부**: 확장자(sh, bat, ps1, py, js, jar, exe, dll …) 또는 시그니처(`#!`, MZ, ELF, CAFEBABE, 중첩 ZIP)가 있으면 등록 거부. 허용: md, txt, json, yaml/yml, csv/tsv, png/jpg/gif/webp.
2. **ZIP**: 메모리에서만 읽고 디스크로 풀지 않음(경로 탐색 무의미). 그래도 `..`/절대경로/역슬래시/드라이브명 거부, 중앙 디렉터리의 **심볼릭 링크 속성 거부**, 암호화 거부, 파일 수/개별/총 크기/압축률 제한.
3. **SKILL.md 는 신뢰하지 않는 콘텐츠**: 등록만으로 어떤 실행도 없음. 인증정보 패턴(API 키/개인키)이 있으면 거부, 외부 URL·네트워크 명령·프롬프트 인젝션 문구는 **위험 플래그**로 관리자 검토 화면에 표시.
4. **권한**: 승인(approve/reject/rollback)은 ADMIN 만. 등록은 USER/ADMIN. 공개 범위 `PUBLIC`(전사) / `TEAM`(제작자와 같은 팀). 본인 Skill 은 평가 불가.
5. **실행 런타임 기본값 = 없음(`RUNTIME_NOT_CONFIGURED`)**. IntelliCEN Code 공식 API 는 확인되지 않아 연동하지 않음. 어댑터 인터페이스만 제공하고, `mock`(테스트용, 항상 시뮬레이션 표시) / `llm`(승인된 LLM Provider, 기본 비활성) 어댑터를 옵션으로 둠.
6. **KPI 집계 제외**: 시뮬레이션(Mock/Mock LLM) 실행, 샘플 Skill, **제작자 본인 실행**, 중복 요청(동일 사용자·Skill·입력 10초 이내). 점수/보상은 계산하지 않음(지표만 제공).

## 5. 단계
STEP 4 Registry 백엔드 → 5/6 프론트엔드 + 등록·승인 → 7 Agent → 8 KPI → 9 Runtime Adapter → 10 3D 진입 → 11 테스트 → 12 문서.
