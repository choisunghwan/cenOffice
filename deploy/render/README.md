# Render 배포 안내

Render에 올리면 `https://cenhub.onrender.com` 같은 주소를 바로 받습니다. HTTPS도 자동입니다.

```
브라우저 ──HTTPS──▶ Render 웹 서비스 cenhub (화면 + API + MCP + WebSocket, 주소 하나)
                        └─▶ Supabase PostgreSQL (DB, 무료·기간 제한 없음)
브라우저 ──영상·음성──▶ LiveKit Cloud (화상 회의)
```

- 화면과 백엔드를 이미지 하나로 묶었습니다 (`deploy/render/Dockerfile`). 그래서 주소가 하나입니다.
- 화상 회의 서버(LiveKit)는 UDP 포트가 필요해 Render에서 돌릴 수 없습니다. **LiveKit Cloud**를 씁니다.

## 1. 저장소를 GitHub에 올리기

Render는 GitHub(또는 GitLab) 저장소에서 코드를 가져옵니다. 비공개 저장소도 됩니다.

```bash
git push origin main
```

## 2. LiveKit Cloud 프로젝트 만들기 (화상 회의)

1. https://cloud.livekit.io 에 가입하고 프로젝트를 만듭니다.
2. **Settings > Keys**에서 API Key를 만들고 다음 값을 적어 둡니다.
   - 프로젝트 주소: `wss://<프로젝트>.livekit.cloud`
   - API Key, API Secret

회의 기능이 당장 필요 없으면 이 단계를 건너뛰어도 됩니다. 나머지 기능은 그대로 동작합니다.

## 3. Supabase DB 만들기

1. https://supabase.com 에 가입하고 **New project**를 만듭니다. Region은 **Southeast Asia (Singapore)**.
2. **Database Password**를 정하고 적어 둡니다.
3. 프로젝트 화면 위쪽 **Connect**를 누르고 **Session pooler**의 값을 봅니다 (Render는 IPv4로만 접속해서 Direct connection은 안 됩니다).
   - host: `aws-0-ap-southeast-1.pooler.supabase.com` 같은 값, port `5432`
   - user: `postgres.<프로젝트 ref>`

테이블은 서버가 처음 켜질 때 Flyway가 만듭니다. 따로 할 일은 없습니다.

## 4. Render에서 Blueprint로 만들기

1. https://dashboard.render.com 에 가입하고 GitHub를 연결합니다.
2. **New > Blueprint**에서 이 저장소를 고릅니다. 저장소 최상위의 `render.yaml`을 읽어 웹 서비스를 만듭니다.
3. 입력하라고 나오는 값을 채웁니다.

| 항목 | 값 |
|---|---|
| `DB_URL` | `jdbc:postgresql://<Session pooler host>:5432/postgres?sslmode=require` |
| `DB_USER` | `postgres.<프로젝트 ref>` |
| `DB_PASSWORD` | 3단계에서 정한 DB 비밀번호 |
| `LIVEKIT_URL` | `wss://<프로젝트>.livekit.cloud` |
| `LIVEKIT_API_URL` | `https://<프로젝트>.livekit.cloud` |
| `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` | 2단계에서 받은 값 |
| `EXT_ALLOWED_ORIGINS` | CenNavi 확장을 쓸 때만 `chrome-extension://<확장 ID>`, 아니면 비워 둠 |

4. **Apply**를 누릅니다. 첫 빌드는 10분 정도 걸립니다.

## 5. 접속

- 주소: Render 대시보드의 `cenhub` 서비스 맨 위에 나오는 `https://….onrender.com`
- 처음 로그인 계정: `admin`, `user`
- 비밀번호: `cenhub` 서비스 > **Environment**의 `OFFICE_ADMIN_PASSWORD`, `OFFICE_USER_PASSWORD` (Render가 임의로 만든 값). 첫 로그인 후 바꾸세요.

**회의 확인:** 회의 화면 오른쪽 위가 "화상회의 준비 완료"인지 봅니다. "연결 필요"로 나오면 LiveKit 값 4개를 다시 확인하세요.

## 업데이트

`main`에 push하면 Render가 자동으로 다시 빌드하고 배포합니다 (`autoDeploy: true`). DB 구조 변경은 Flyway가 시작할 때 적용합니다.

## 요금 (꼭 확인)

`render.yaml`은 처음 써 보기 좋게 **무료(free)** 로 되어 있습니다. 실제로 쓰기 전에 대시보드에서 올리세요.

| | 무료의 제약 | 실사용 권장 |
|---|---|---|
| 웹 서비스 | 15분간 접속이 없으면 잠들어 다음 접속이 30초~1분 걸림. 메모리 512MB | Starter 이상 |

Supabase 무료 DB는 기간 제한이 없습니다. 다만 용량 500MB이고, 1주일간 접속이 없으면 일시 정지됩니다(대시보드에서 다시 켜면 됨). 자동 백업이 필요하면 Pro로 올리세요.

LiveKit Cloud는 무료 사용량이 있고, 넘으면 사용량만큼 요금이 붙습니다.

## 내 도메인을 쓰고 싶을 때

`cenhub` 서비스 > **Settings > Custom Domains**에서 도메인을 추가하고 안내대로 DNS를 연결합니다. 그다음 **Environment**에 아래 두 값을 넣습니다 (넣지 않으면 onrender.com 주소 기준으로 동작).

- `CORS_ORIGINS=https://내도메인`
- `APP_BASE_URL=https://내도메인` (알림 메일 안의 링크)

## 문제 해결

| 증상 | 확인할 것 |
|---|---|
| 배포가 실패함 | 서비스 > **Events / Logs**의 빌드 로그 |
| 메모리 부족으로 재시작됨 (무료 512MB) | 요금제를 Starter로 올리기 |
| 서버가 DB에 연결하지 못함 | `DB_URL`이 Session pooler 주소인지, 끝에 `?sslmode=require`가 있는지, `DB_USER`가 `postgres.<ref>` 형식인지. Supabase 프로젝트가 일시 정지되지 않았는지 |
| 로그인할 때 403 | 내 도메인을 붙였다면 `CORS_ORIGINS` 설정 |
| 회의 "연결 필요" | LiveKit 값 4개, LiveKit Cloud 프로젝트 상태 |
