# AI HUB (AI Agent & Skill Hub) — 구현 결과

분석·설계 결정은 [AI_HUB_ANALYSIS.md](AI_HUB_ANALYSIS.md) 참고. 이 문서는 구현된 범위와 검증 결과, 운영 방법입니다.

## 구조

```
WORKSPACE > AI HUB (React)  ──┐
3D OFFICE > AI LAB 터미널  ───┤ 같은 REST API (/api/hub/**)
                              ▼
Spring Boot  hub 패키지  ─ SkillService / AgentService / ExecutionService / AnalyticsService
   ├ SkillPackageReader   업로드 검증(SKILL.md·ZIP), 위험 플래그, 인증정보 차단
   ├ Registry (hub_* 테이블, Flyway V2)   Skill·Version·Tag·Review·Approval / Agent·Version·Mapping / Execution·UsageEvent
   └ runtime.AgentRuntimeAdapter   none(기본) | mock(테스트) | llm(승인된 LLM Provider) | (향후) IntelliCEN Code
```

Skill 과 Agent 는 테이블·서비스·API 모두 분리되어 있고(`hub_skills`/`hub_agents`), Agent 는 승인된 Skill 을 `hub_agent_skills` 로 조합합니다.

## 구현된 기능 (테스트/브라우저로 확인한 것)

- **Skill 등록**: SKILL.md 업로드, ZIP 업로드, 웹 에디터. YAML Frontmatter 를 파싱해 구조화 저장하고 원본(SKILL.md/ZIP)을 그대로 보존(sha256).
- **검증**: name/description/version/tags 형식, 중복 이름, 카테고리, 파일 크기·개수·압축률, 경로 탐색·절대경로·역슬래시, **심볼릭 링크(ZIP 중앙 디렉터리 검사)**, 암호화 ZIP, 중첩 ZIP, 실행 파일/스크립트(확장자+시그니처), 인증정보(API 키·개인키) 거부. 외부 URL·네트워크 명령·프롬프트 인젝션 의심 문구는 **위험 플래그**로 관리자 검토 화면에 표시(차단 아님).
- **승인 워크플로**: DRAFT → PENDING_REVIEW → APPROVED/REJECTED. 관리자만 승인/반려(반려 사유 필수), 본인 등록분은 다른 관리자가 승인. 새 버전은 승인 전까지 공개 버전에 영향 없음. 승인된 이전 버전으로 **롤백**, 승인 이력 조회.
- **버전 관리**: 버전별 원본 보존·조회·다운로드, 버전 라벨 중복 방지/자동 증가.
- **공개 범위·접근 제어**: PUBLIC(전사) / TEAM(제작자와 같은 팀). 볼 수 없는 리소스는 403 이 아니라 **404 로 존재를 숨김**.
- **검색/필터**: 통합 검색(이름·설명·태그, LIKE 와일드카드 안전 처리), 카테고리·제작자·태그·상태, 최신순/인기순, 페이지네이션.
- **평가**: 1~5점+의견, 사용자당 1건(수정), 제작자 본인 평가 불가.
- **Agent**: 구성(설명·담당 업무·실행 설정 JSON 화이트리스트·Skill 연결/해제), 구성 변경 시 버전 증가+스냅샷+재승인.
- **실행 구조**: `AgentRuntimeAdapter`(실행 요청/상태·결과 조회/취소 가능 여부/실패 처리) + 실행 이력. 기본은 `RUNTIME_NOT_CONFIGURED` 이며 이때 UI 는 "실행 연동 준비 중"을 표시하고 요청을 접수하지 않음.
- **KPI**: 실제 이벤트(`hub_usage_events`) 기반. 제작자별(등록/승인 수, 요청·성공·실패, 고유/재사용자, 7일/30일, 월별, 버전별, 피드백), Skill별, 전체, 인기 순위. **점수·보상은 계산하지 않음.**
- **집계 제외**(투명하게 건수 표시): 시뮬레이션(Mock) 실행, 샘플 Skill/Agent, 제작자 본인 실행, 10초 이내 동일 입력 중복(거부). 실행 요청 분당 10건 제한.
- **감사 로그**: 등록·거부 사유·승인/반려/롤백·다운로드·실행 요청/종료/취소 모두 기록.
- **3D 연동**: 북쪽 벽의 AI LAB 터미널(보라색 화면). E 키/클릭 → WORKSPACE 의 AI HUB 로 이동. 충돌체 1개 추가 외 기존 3D 로직 변경 없음.
- **샘플**: `error-detective`, `requirement-analyst` Skill + 샘플 Agent 2종. `sample=true`, 시스템 계정(`hub-sample`) 소유, 모든 KPI 에서 제외.

## IntelliCEN Code 연동 가능 여부

**연동하지 않았고 연동된 것처럼 표시하지도 않습니다.** 공식 API 가 확인되지 않았습니다. `AgentRuntimeAdapter` 구현체를 추가하고 `RuntimeRegistry` 에 등록하면 Registry/UI/KPI 변경 없이 연결할 수 있습니다. 현재 선택 가능한 런타임:

| `HUB_RUNTIME` | 동작 | KPI 집계 |
|---|---|---|
| `none` (기본) | 실행 불가, "실행 연동 준비 중" | – |
| `mock` | 시뮬레이션 결과(테스트 전용). `APP_ENV=PROD` 에서는 로드 거부 | 제외 |
| `llm` | 승인된 LLM Provider(`LLM_PROVIDER`)로 텍스트 생성만 수행(도구/파일/네트워크/명령 실행 권한 없음). Provider 가 Mock 이면 시뮬레이션 | 실제 LLM 이면 집계 |

`llm` + 외부 LLM(Gemini 등)은 **Skill 지침과 입력이 외부 서비스로 전송**됩니다. 사내 정보 반출 정책 검토 후에만 켜세요.

## DB 변경 (Flyway)

- `V1__baseline.sql`: 기존 Hibernate 스키마의 기준선(내용 없음). 기존 DB 는 `baseline-on-migrate` 로 V1 처리 — 실제 파일 DB 에서 확인(기존 계정/데이터 보존).
- `V2__ai_hub.sql`: `hub_categories, hub_skills, hub_skill_versions, hub_skill_tags, hub_skill_reviews, hub_approval_history, hub_agents, hub_agent_versions, hub_agent_skills, hub_executions, hub_usage_events` 신규. 기존 테이블 변경·삭제 없음. 사용자 참조는 기존 관례대로 `username` 문자열.
- **`hub_*` 는 Flyway 전용**: Hibernate `ddl-auto=update` 가 H2 에서 컬럼을 기본 길이로 되돌리는 문제(5MB 패키지 컬럼이 255바이트로 축소)를 발견해 `FlywayOwnedTablesFilterProvider` 로 제외했습니다.

## API (`/api/hub`)

| 영역 | 엔드포인트 |
|---|---|
| 메타 | `GET /me` · `GET /categories` · `GET /runtime` |
| Skill | `GET /skills`(q,category,owner,tag,status,mine,sort,page,size) · `GET /skills/{id}[?versionId]` · `POST /skills`(JSON) · `POST /skills/upload`(multipart) · `PUT /skills/{id}` · `DELETE /skills/{id}`(보관) · `GET /skills/{id}/download[?versionId]` |
| 버전/승인 | `GET /skills/{id}/versions` · `POST /skills/{id}/versions` · `POST /skills/{id}/versions/upload` · `POST …/versions/{vid}/submit` · `…/approve` · `…/reject` · `POST /skills/{id}/rollback` · `GET /skills/{id}/history` · `GET /review-queue`(ADMIN) |
| 평가 | `GET/POST /skills/{id}/reviews` |
| Agent | `GET /agents` · `GET /agents/{id}` · `POST /agents` · `PUT /agents/{id}` · `DELETE /agents/{id}` · `POST/DELETE /agents/{id}/skills/{skillId}` · `POST /agents/{id}/submit|approve|reject` · `GET /agents/{id}/history` · `GET /agents/review-queue`(ADMIN) |
| 실행 | `POST /executions` · `GET /executions/{id}` · `POST /executions/{id}/cancel` · `GET /executions` |
| 통계 | `GET /analytics/overview` · `/analytics/popular` · `/analytics/skills/{id}` · `/analytics/creators/me` · `/analytics/creators/{username}`(본인/ADMIN) |

권한: 조회=인증 사용자(공개 범위 내), 등록·수정·실행=USER/ADMIN(자기 리소스 제작자), 승인·반려·롤백·검토 대기열=ADMIN. CREATOR 는 별도 역할이 아니라 USER 가 등록한 리소스의 제작자로 매핑(기존 `users.role` 제약 보존).

## 신규/수정 파일

- 신규 백엔드: `com/cenoffice/hub/*`(엔티티 11, 리포지토리 11, `SkillPackageReader`, `SkillService`, `AgentService`, `ExecutionService`, `AnalyticsService`, `HubSampleSeeder`, 컨트롤러 3, `HubDtos`), `hub/runtime/*`(어댑터 인터페이스+none/mock/llm+레지스트리), `config/FlywayOwnedTablesFilterProvider`, `db/migration/V1,V2`, `hub-samples/*/SKILL.md`
- 수정 백엔드: `pom.xml`(Flyway), `application.yml`, `SecurityConfig`(다운로드 파일명 헤더 노출)
- 신규 프론트: `hubApi.ts`, `hub/*`(HubPage, SkillDetailView, SkillForm, AgentViews, CreatorDashboard, ReviewQueue, shared)
- 수정 프론트: `store.ts`(workspacePage, hub 진입), `workspace/Workspace.tsx`(AI HUB 메뉴), `workspace/kit.tsx`(요청 경쟁 상태 수정, 작은 Select), `game/layout.ts`·`game/Office.tsx`(AI LAB 터미널)
- 신규 테스트: `hub/SkillPackageReaderTest`(10), `hub/HubApiTest`(12), `hub/HubRuntimeTest`(4), `hub/RuntimeRegistryTest`(4)

## 테스트 결과

- 백엔드 `mvn test`: **63건 통과** (기존 33 + 신규 30). 기존 Phase 1~3 회귀 없음.
- 브라우저(Edge, 2계정): 등록(웹 에디터) → 잘못된 SKILL.md 거부 사유 표시 → 관리자 승인 대기열(위험 플래그) → 승인 → 검색·카테고리 필터·상세·사용 방법/예시·다운로드(파일명)·SKILL.md 미리보기 → "실행 연동 준비 중" → 평가(본인 평가 거부) → 제작자 대시보드 → 3D OFFICE 에서 AI HUB 진입 → 기존 상호작용 정상, 콘솔 오류 없음(의도한 400 제외).
- 실제 LLM 런타임(Gemini, `HUB_RUNTIME=llm`)으로 Skill 실행 성공, KPI 집계(요청 1/성공 1), 제작자 본인 실행은 집계 제외 확인.

## 미구현 / 한계

- **IntelliCEN Code 연동 없음**(공식 API 미확인). 기본 설정은 실행 불가.
- Agent 실행은 선택된 Skill 지침을 한 번에 LLM 에 전달하는 단순 구조(다단계 계획·도구 호출 없음). `maxSteps`/`temperature` 등 실행 설정은 저장·검증만 하고 적용하지 않음.
- 부서 접근 제어는 PUBLIC/TEAM 두 단계(여러 팀·직급 단위 권한 없음). 관리자가 한 명뿐이면 본인 등록분을 승인할 수 없음(의도된 정책).
- 위험 플래그는 정규식 기반 휴리스틱 — 사람의 검토를 대체하지 않음. 실행 파일 검사는 확장자+주요 시그니처만.
- KPI 는 원자료 지표만 제공하며 점수·보상 산식은 없음(기준 미확정). 통계는 메모리 집계(수천 건 규모 가정), 대규모에서는 DB 집계/배치로 전환 필요.
- Skill 삭제는 보관(ARCHIVED) 처리뿐이며 영구 삭제·복구 UI 없음. 메시지/알림, 팀 단위 Skill 폴더 구조, 마켓플레이스식 추천은 없음.
- PostgreSQL 에서는 검증하지 못함(H2 PostgreSQL 모드 + 마이그레이션 SQL 은 표준 문법 사용). 운영 전 PostgreSQL 에서 `V1~V2` 적용을 확인하세요.

## 실행 방법

```powershell
.\scripts\run-backend.ps1          # 백엔드 (기본 HUB_RUNTIME=none)
cd frontend; npm run dev           # http://localhost:5173  →  WORKSPACE > AI HUB 또는 3D 의 AI LAB 터미널
```

환경변수: `HUB_RUNTIME`(none|mock|llm), `APP_ENV`(DEV|STG|PROD), `LLM_PROVIDER`/`GEMINI_API_KEY` 등(LLM 런타임 사용 시). 테스트: `cd backend && mvn test`.

## 내장 업무 도구 (실사용)

`hub-builtin` 소유의 신뢰된 내장 도구입니다. 모두 **규칙 기반 로컬 처리**이며 LLM·외부 전송이 없습니다. 결과는 검토용 초안입니다.

| 이름 | 용도 | 입력 |
|---|---|---|
| FP 산정 도우미 (`cen-fp-estimator`) | 기능 목록의 EI/EO/EQ 후보 분류 | 파일(DOCX/XLSX/TXT) |
| 제안서 작성 도우미 (`cen-proposal-writer`) | 요구사항 대응표·제안서 목차 초안 | 파일 |
| 오류 로그 분석 (`cen-log-analyzer`) | 예외 연쇄·근본 원인 후보·확인 순서 | 텍스트 |
| SQL 점검 (`cen-sql-reviewer`) | WHERE 없는 변경, SELECT *, 인덱스 미사용 패턴 등 | 텍스트 |
| 회의록 정리 (`cen-meeting-minutes`) | 결정·액션 아이템(담당/기한)·이슈 분류 | 텍스트 |
| 주간 업무보고 정리 (`cen-weekly-report`) | 완료/진행/예정/이슈 분류, 진행률 평균 | 텍스트 |
| 테스트 케이스 초안 (`cen-test-case-writer`) | 정상/예외/경계/권한 시나리오 표 | 텍스트 |

- 추가 방법: `backend/src/main/resources/hub-builtins/<name>/SKILL.md` 작성 → `BuiltinSkillSeeder.NAMES` 등록 → `BuiltinRuntimeAdapter` 에 처리 추가 → 프론트 `toolNames.ts`(표시 이름), 텍스트 도구는 `TextTool.tsx` 에 예시 등록.
- 텍스트 도구 입력 한도는 15,000자입니다.

## 결과 파일 내보내기 (xlsx / docx / pptx / pdf)

도구 결과 화면의 '파일로 받기' 버튼 → `POST /api/hub/documents/export` (`{format, title, markdown}`). 서버에서 변환만 하고 저장·외부 호출은 하지 않습니다.
- Markdown 의 제목·문단·목록·표를 변환합니다. 엑셀은 모든 값을 문자열 셀로 써서 수식 주입을 막습니다.
- PDF 는 한글 폰트가 필요합니다. 기본 탐색 경로(Windows 맑은 고딕, Linux NanumGothic/Noto CJK)에 없으면 `office.docs.pdf-font` 에 TTF 경로를 지정하세요. 없으면 501 로 안내합니다.
- HWPX(한글 최신 형식)도 지원합니다: 제목·문단·목록·표. 한컴오피스 2018 에서 열림을 확인했습니다. 구형 `.hwp` 는 만들지 못합니다(한글에서 HWPX 를 열어 다른 이름으로 저장하면 됩니다).


## 외부 AI 도구 연동

화면: 왼쪽 메뉴 'AI 도구에서 쓰기'.

### 방법 A — 스킬 파일 (Claude Code / Codex)
`GET /api/hub/skills/export.zip` — 로그인한 사용자가 볼 수 있는 **승인된** Skill 을 `<name>/SKILL.md` 구조로 묶어 내려줍니다(샘플은 `includeSamples=true` 일 때만). 내장 도구는 항상 최신 번들 SKILL.md(`## 수행 지침` 포함)를 담습니다. `~/.claude/skills`, `~/.codex/skills` 에 풀면 됩니다.

### 방법 B — MCP 서버 (`POST /mcp`)
- 개인 액세스 토큰(`cen_pat_…`)으로 인증합니다. 발급/폐기: `/api/hub/tokens` (로그인 JWT 필요). 서버에는 SHA-256 해시만 저장되고 원문은 발급 응답에서 한 번만 보입니다. 기본 90일, 최대 365일, 사용자당 10개.
- Streamable HTTP 의 단일 JSON 응답 방식(`initialize`, `ping`, `tools/list`, `tools/call`). 승인된 Skill 마다 도구 1개(`input`, 선택 `engine`).
- 호출은 웹과 같은 `ExecutionService` 를 거칩니다(권한, 승인 상태, 중복·속도 제한, 감사, KPI).
- 브라우저 출처(Origin 헤더) 요청은 403, 로그인 JWT 는 `/mcp` 에서 사용할 수 없습니다.
- Claude Code: `claude mcp add --transport http cen-ai-hub <서버>/mcp --header "Authorization: Bearer <토큰>"`
- Codex: `~/.codex/config.toml` 의 `[mcp_servers.cen-ai-hub]` 에 `url`, `bearer_token_env_var`.

## AI 모델 실행 (`HUB_AI_ENGINE`)
- 기본값 `false`. `true` 로 켜면 `app.llm.*`(Gemini/OpenAI/Anthropic) 로 승인된 Skill 을 실행할 수 있습니다. **입력이 해당 외부 서비스로 전송됩니다** — 운영 적용 전 보안 승인이 필요합니다.
- 내장 도구는 요청마다 `engine`(`rule` 기본 / `ai`)을 고릅니다. 별도 런타임이 없을 때 일반 Skill 은 AI 엔진으로 실행됩니다.
- `GET /api/hub/ai-engine` 이 사용 가능 여부와 전송 안내 문구를 돌려주고, 화면이 선택지와 경고를 표시합니다.
- Mock LLM 이면 시뮬레이션으로 표시되어 KPI 에서 제외됩니다. `scripts/run-backend.ps1` 은 개발용으로 이 값을 true 로 둡니다.
