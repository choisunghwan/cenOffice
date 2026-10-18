# CEN OFFICE

웹 기반 3D AI 스마트 오피스 플랫폼. 현재 **Phase 1(3D) + Phase 2(백엔드) + Phase 3(WORKSPACE, AI PM, 멀티플레이어/채팅)** 까지 구현되어 있습니다.

```
frontend/   React + Three.js(R3F) + Zustand + Tailwind
backend/    Spring Boot 3 / Java 21 / JPA / WebSocket / JWT
scripts/    run-backend.ps1  (Windows 백엔드 실행 스크립트)
.tools/     (선택) 로컬 JDK 21 + Maven — git 제외 권장
```

## 실행

### 1) 백엔드 (포트 8080)
```powershell
.\scripts\run-backend.ps1
```
- `.tools\` 의 JDK 21/Maven 이 있으면 사용하고, 없으면 시스템 `java`(21+)/`mvn` 을 사용합니다.
- `OFFICE_ADMIN_PASSWORD`, `OFFICE_USER_PASSWORD` 환경변수가 없으면 최초 1회 임의 생성되어 `backend\.local-credentials.txt`(git 제외)에 저장됩니다. 계정: `admin`(관리자), `user`(일반).
- 기본 DB는 로컬 파일 H2(PostgreSQL 호환 모드, `backend/data/`)라서 별도 설치 없이 실행됩니다.

직접 실행하려면: `cd backend && mvn spring-boot:run`, 테스트: `mvn test`

### 2) 프론트엔드 (포트 5173)
```bash
cd frontend
npm install
npm run dev
```
접속하면 기본 화면은 **WORKSPACE** 이고 로그인 창이 뜹니다. 상단에서 `WORKSPACE` / `3D OFFICE` 를 전환합니다. **"로컬 모드로 둘러보기"** 를 누르면 백엔드 없이도 3D 사무실·조명·Mock AI는 동작합니다.

## 환경변수

| 변수 | 설명 | 기본값 |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | PostgreSQL 사용 시 지정 (예: `jdbc:postgresql://localhost:5432/cenoffice`) | 파일 H2 |
| `JWT_SECRET` | JWT 서명키(32자 이상). 비우면 기동 시 임의 생성(재시작 시 로그인 만료) | 임의 |
| `JWT_TTL_MINUTES` | 토큰 유효 시간 | 480 |
| `OFFICE_ADMIN_PASSWORD`, `OFFICE_USER_PASSWORD` | 시드 계정 비밀번호 | 임의 생성 |
| `CORS_ORIGINS` | 허용 프론트 주소 | `http://localhost:5173` |
| `DEVICE_COMMAND_TIMEOUT_SECONDS` | 장비 응답 대기 한도 | 10 |
| `MOCK_MIN_LATENCY_MS` / `MOCK_MAX_LATENCY_MS` / `MOCK_FAILURE_RATE` | Mock 장비 지연/실패율(실패 처리 테스트용) | 300/900/0.0 |
| `VITE_API_URL` (프론트) | 백엔드 주소 | `http://localhost:8080` |

## 조작

| 입력 | 동작 |
|---|---|
| W / S | 바라보는 방향으로 전진 / 후진 |
| A / D | 시점(카메라+캐릭터) 좌우 회전 (`Player.tsx`의 `TURN_MODE=false`면 좌우 평행이동) |
| 마우스 드래그 / 휠 | 카메라 회전 / 줌 |
| E | 가까운 오브젝트 상호작용 (조명 스위치, 에어컨, 서버실, 회의실, AI 직원 …) |
| ESC | 패널 닫기 |

## 구현 현황

**구현됨**
- 3D 사무실, 캐릭터 이동/충돌/카메라, 벽 투명화, 조명 ON/OFF (Phase 1)
- JWT 로그인(ADMIN/USER 권한), REST API, WebSocket 실시간 이벤트(`device.state`, `command.update`, `reservation.changed`)
- 장비 관리: 명령 상태 `REQUESTED → SENT → CONFIRMED / FAILED / TIMEOUT`.
  **장비가 확인(보고)한 값만 화면/DB 상태에 반영**하며, 전송했다는 이유로 성공 처리하지 않음. 응답 없으면 TIMEOUT.
- `DeviceAdapter` 인터페이스 + `MockDeviceAdapter`(지연·실패·에어컨 온도 변화 시뮬레이션)
- 조명 / 에어컨(전원·목표온도·냉난방·현재온도) / 회의실 예약(조회·생성·취소·중복 방지) / 서버실 모니터링 패널
- 감사 로그(로그인, 장비 명령, 예약), 시작 시 장비 실제 상태 동기화
- AI 직원의 조명 제어는 사용자 승인 후 같은 명령 파이프라인을 사용 (온라인이면 장비 확인까지 대기)
- 백엔드 테스트 4건 (인증, 장비 명령 확정 흐름, 잘못된 명령 거부, 예약 중복)

**아직 아닌 것 (정직하게)**
- 서버 모니터링 데이터는 **Mock**입니다 (`ServerMonitor` 인터페이스만 분리됨).
- `MqttDeviceAdapter` / `HttpDeviceAdapter` 는 미구현 → Phase 4. 해당 타입 장비에 명령하면 FAILED 처리됩니다.
- 엔티티 `Office / Space / OfficeObject / AiAgent / AiTask / AiTaskExecution` 은 아직 없음 (Device.objectId 로 3D 매핑만 제공). 개인 책상 업무 대시보드는 Phase 3.
- AI 직원은 여전히 Mock(LLM/Tool Calling 미연동, 업무 기록 미저장) → Phase 3.
- PostgreSQL 은 이 PC에 없어 실제 접속 테스트는 못 했습니다(H2 PostgreSQL 모드로만 검증). 스키마는 `ddl-auto=update` 입니다.
- 예약 시간 중복 방지는 SERIALIZABLE 트랜잭션 기반이며 단일 인스턴스를 가정합니다.
- 로그인 시도 횟수 제한(rate limit)은 아직 없습니다.

## Phase 3A (WORKSPACE)

- 메뉴: Home · Projects · Requirements · AI Agents(Mock) · Development/Testing/Deployment(준비 중) · Monitoring(Mock) · Office Management · Settings
- 엔티티/API: Project, Requirement, ProjectTask, AiAgent, AiTask, AgentExecution, ApprovalRequest (CRUD, 작업 담당 AI 지정, 작업/요구사항 이력 = 감사 로그 기반, 승인 요청/결정)
- **환경 경계(서버 강제)**: DEV 읽기·테스트·개발 / STG 읽기·테스트 / PROD 읽기 전용이며 **어떤 AI 에이전트에도 PROD 권한 없음**. 배포(DEPLOY) 권한과 운영 배포 실행 기능은 존재하지 않음.
- 승인: 요청자 본인은 승인 불가(관리자 제외), 이미 처리된 요청은 재처리 불가.
- AI 작업 자동 실행은 **AI PM 요구사항 분석(DRAFT_SPEC)만** 지원합니다. 그 외 유형은 접수(QUEUED)·승인 기록까지만 저장됩니다.

## AI HUB (Skill / Agent 허브)

직원이 만든 AI Skill(SKILL.md)과 Agent 를 등록·검색·승인·평가하는 사내 허브. WORKSPACE > AI HUB 또는 3D 의 **AI LAB 터미널**(북쪽 벽)에서 엽니다. 자세한 내용: [docs/AI_HUB.md](docs/AI_HUB.md), 분석/설계: [docs/AI_HUB_ANALYSIS.md](docs/AI_HUB_ANALYSIS.md)

- 업로드(SKILL.md/ZIP)는 신뢰하지 않는 입력으로 검증(경로 탐색·심볼릭 링크·압축 폭탄·스크립트/실행 파일·API 키 거부). 등록만으로는 아무것도 실행되지 않고, 관리자 승인 후 공개됩니다.
- **실행 런타임은 기본 없음**(`HUB_RUNTIME=none` → UI 에 "실행 연동 준비 중"). IntelliCEN Code 는 공식 API 미확인으로 연동하지 않았고, `mock`(테스트용·KPI 제외) / `llm`(승인된 LLM Provider, 외부 전송 주의) 어댑터만 선택 가능합니다.
- DB 는 **Flyway** 로 관리하는 `hub_*` 테이블(`V2__ai_hub.sql`)을 추가했고 기존 테이블은 건드리지 않습니다. 기존 DB 는 자동으로 기준선(V1) 처리됩니다.
- KPI 는 실제 이벤트만 집계(시뮬레이션·샘플·제작자 본인 실행 제외)하며 점수·보상은 산정하지 않습니다.

## AI PM (아리) — Phase 3B

흐름: 요구사항 접수 → 분석 → (부족하면 질문, 최대 2라운드) → 기능 명세 초안 → 요구사항/작업 목록 생성 → **사용자 승인 요청** → 승인되면 서버가 검증 후 프로젝트에 등록. WORKSPACE > AI Agents 의 "AI PM 아리" 패널에서 사용합니다.

- **LLM Provider 는 인터페이스**(`LlmProvider`)로 분리: `MockLlmProvider`(기본, 규칙 기반) / `GeminiLlmProvider` / `AnthropicLlmProvider` / `OpenAiLlmProvider`. 실제 LLM 은 `LLM_PROVIDER=gemini` + `GEMINI_API_KEY`(모델 `GEMINI_MODEL`, 기본 gemini-2.5-flash), `anthropic` + `ANTHROPIC_API_KEY`, `openai` + `OPENAI_API_KEY`(`OPENAI_MODEL`, 기본 gpt-4o-mini) 환경변수가 있을 때만 사용되며, **요구사항 원문이 외부 서비스로 전송**됩니다. 키가 없으면 자동으로 Mock.
- **AI 출력은 신뢰하지 않음**: 서버가 스키마·개수·길이·허용 값을 검증(`PlanValidator`)하고, 통과한 계획만 저장합니다. 승인 요청에는 계획의 SHA-256 만 담기며, 승인 시 서버가 저장된 계획의 해시를 다시 대조하고 재검증·환경 권한 확인 후에만 적용합니다(어긋나면 적용 중단 + 승인도 롤백).
- 승인 전에는 프로젝트 데이터가 전혀 바뀌지 않습니다. 수동 승인 요청(`MANUAL_REVIEW`)은 어떤 변경도 적용하지 않습니다.
- 모든 단계는 AgentExecution/AiTask.pmState/감사 로그에 기록됩니다. PM 작업은 DEV 환경에서만 가능하고, 운영 배포 기능은 없습니다.
- **Gemini 는 실제 키로 전체 흐름(질문→계획→승인→등록)을 검증했습니다.** Anthropic/OpenAI 는 유효한 키로 성공 호출을 검증하지 못했습니다(OpenAI 는 호출·오류 처리 경로만 확인, 응답 파싱은 단위 테스트). 키는 `backend/.local-credentials.txt`(git 제외)에 `LLM_PROVIDER=gemini`, `GEMINI_API_KEY=...` 같은 줄로 넣으면 `scripts/run-backend.ps1` 이 읽습니다. Mock 흐름은 테스트·브라우저로 검증했습니다.
- 스키마: 상태 컬럼은 문자열로 저장하고, 예전 DB 의 낡은 CHECK 제약은 시작 시 `SchemaFixer` 가 제거합니다(임시 조치 — 운영 전 Flyway 등 정식 마이그레이션 필요).

## 멀티플레이어 / 채팅

- 같은 `/ws` 연결로 접속자·위치를 동기화합니다. 클라이언트가 보낼 수 있는 메시지는 `pos`(위치)와 `hb`(생존 신호)뿐이고, **사용자 신원은 항상 JWT 에서만** 가져옵니다(메시지에 다른 username 을 넣어도 무시).
- 위치는 서버 메모리에만 저장하고(DB 저장 없음) 100ms 단위로 변경분만 방송합니다. 범위·이동 속도 검증을 통과하지 못한 값은 버립니다.
- 15초 동안 신호가 없으면 접속 종료로 처리합니다. 같은 계정으로 다시 접속하면 **새 접속이 우선**이며 이전 연결은 코드 4001 로 종료됩니다(자동 재접속하지 않고 안내만 표시).
- 채팅: 전체 / 1:1 / AI(아리). 메시지는 DB 에 저장되고 이전 대화를 조회할 수 있습니다. 1:1·AI 대화는 참여자만 조회·전송·수신할 수 있고(서버가 매 요청 확인, 거부는 감사 로그), 500자 제한·5초당 5건 제한이 있습니다. 화면에는 항상 **텍스트로만** 출력합니다.
- 이름표(이름·팀 / AI 배지·역할·작업 상태), 말풍선(6초 후 사라짐, 80자 초과 시 줄임)을 3D 캐릭터 위에 표시합니다.
- AI 채팅은 Mock 응답이며 어떤 작업도 실제로 수행하지 않습니다.

## 보안/안정성 (Phase 2 점검 결과)

- 감사 로그는 호출 트랜잭션이 롤백돼도 남도록 별도 트랜잭션으로 저장(이전에는 거부된 AI 작업 기록이 유실됨)
- 로그인 실패 5회(계정+IP) / IP당 20회 → 10분 잠금(429), 메모리 기반(단일 인스턴스)
- 같은 장비에 처리 중인 명령이 있으면 새 명령 거부(409)
- WebSocket: 토큰 필수, 만료 시 연결 종료, 클라이언트 메시지는 거부(1008). 이벤트는 인증된 전원에게 방송됨(사용자별 구독 제한은 없음)
- 권한 거부(ACCESS_DENIED), 로그인 잠금이 감사 로그에 기록됨
- 알려진 한계: DB 마이그레이션 도구(Flyway) 미도입(`ddl-auto=update`) — PostgreSQL 운영 전 도입 필요 / JWT 는 발급 후 사용자 삭제·권한 변경이 만료 전까지 반영되지 않음 / 승인 강제는 서버 AI 실행(3B)에서 연결 예정(현재 브라우저 Mock AI 의 조명 승인은 클라이언트 흐름이며, 실제 제어는 서버의 인증·권한·명령 파이프라인을 거침)

## 설계 메모
- 충돌은 `@react-three/rapier` 대신 경량 자체 구현(`frontend/src/game/collision.ts`)을 사용합니다.
- 색상/조명/상태색은 `frontend/src/theme.ts` 한 곳에서 관리합니다.
- 3D 오브젝트 ↔ 장비 매핑: `Device.objectId` = 프론트 `layout.ts` 의 interactable id.
