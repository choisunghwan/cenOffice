# CEN HUB 서버 배포 안내

리눅스 서버 한 대에 Docker Compose로 CEN HUB 전체를 띄웁니다. HTTPS와 화상 회의도 포함됩니다.

```
브라우저 ──HTTPS──▶ web(Caddy) ─┬─ /api, /ws ─▶ backend(Spring Boot) ─▶ postgres
                                └─ 회의 도메인 ─▶ livekit
브라우저 ──UDP 7882 / TCP 7881 / TURN 3478──▶ livekit   (영상·음성)
```

| 컨테이너 | 하는 일 |
|---|---|
| `web` | React 화면 제공, HTTPS 인증서 자동 발급·갱신, 요청 전달 |
| `backend` | API 서버 (`APP_ENV=PROD`) |
| `postgres` | 업무 DB. 데이터는 `pgdata` 볼륨에 저장됩니다 |
| `livekit` | 화상 회의 미디어 서버 (TURN 포함) |

## 1. 서버 준비

- Ubuntu 22.04 또는 24.04, 2~4코어, 메모리 4~8GB (회의 동시 인원이 많으면 늘리세요)
- 고정 공인 IP

**방화벽 / 보안 그룹에서 열 포트**

| 포트 | 용도 |
|---|---|
| 22/TCP | SSH (관리자 IP만 허용 권장) |
| 80/TCP, 443/TCP, 443/UDP | 웹, HTTPS 인증서 발급 |
| 7881/TCP, 7882/UDP | 회의 영상·음성 |
| 3478/UDP | TURN (회사 방화벽 뒤 참석자) |

5432(DB), 8080(백엔드), 7880(LiveKit API)은 **열지 않습니다**. 컨테이너끼리만 통신합니다.

## 2. 도메인 연결 (DNS)

도메인 관리 화면에서 A 레코드 두 개를 서버 IP로 연결합니다.

```
hub.회사도메인   →  서버 IP     (업무 화면)
meet.회사도메인  →  서버 IP     (화상 회의)
```

연결 확인: `nslookup hub.회사도메인` 결과가 서버 IP인지 봅니다. 연결 전에 실행하면 HTTPS 인증서 발급이 실패합니다.

## 3. Docker 설치 (서버에서 1회)

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER   # 다시 로그인하면 sudo 없이 docker 사용
```

## 4. 코드 받기 + 설정

```bash
git clone <저장소 주소> cenhub
cd cenhub/deploy
sh init-env.sh        # 도메인 입력 → .env 생성, 처음 로그인 비밀번호 출력
```

메일·AI를 쓰려면 `.env`를 열어 해당 항목을 채웁니다 (`nano .env`).

## 5. 실행

```bash
docker compose up -d --build     # 첫 빌드는 5~10분 정도 걸립니다
docker compose ps                # 4개가 모두 running(healthy) 인지 확인
docker compose logs -f backend   # 'Started' 가 보이면 준비 완료 (Ctrl+C 로 나가기)
```

브라우저에서 `https://hub.회사도메인` 으로 접속해 admin 계정으로 로그인합니다.

**회의 확인:** 회의를 하나 만들고 다른 PC(가능하면 휴대폰 데이터 망)에서 참여 코드로 들어가 영상·음성이 서로 보이는지 확인합니다.

## 업데이트 배포

```bash
cd cenhub && git pull
cd deploy && docker compose up -d --build
```

DB 구조 변경은 백엔드가 시작하면서 Flyway로 자동 적용합니다.

## 백업 / 복구

```bash
# 백업 (매일 cron 등록 권장)
docker compose exec -T postgres pg_dump -U cenhub cenhub | gzip > backup-$(date +%F).sql.gz

# 복구 (빈 DB 에)
gunzip -c backup-2026-11-23.sql.gz | docker compose exec -T postgres psql -U cenhub cenhub
```

백업 파일은 서버 밖(다른 저장소)에도 복사해 두세요.

## 문제 해결

| 증상 | 확인할 것 |
|---|---|
| HTTPS 접속이 안 됨 | `docker compose logs web` — DNS 연결, 80/443 포트 개방 여부 |
| 로그인 화면은 뜨는데 로그인 실패 / 네트워크 오류 | `docker compose logs backend`, `.env`의 `APP_DOMAIN`이 실제 접속 주소와 같은지 (바꿨다면 `--build` 로 다시 실행) |
| 회의 입장은 되는데 영상·음성이 안 나옴 | 7881/TCP, 7882/UDP, 3478/UDP 포트 개방, `docker compose logs livekit` |
| 회의 기능이 '준비 중'으로 나옴 | `LIVEKIT_API_SECRET`이 32자 이상인지, livekit 컨테이너가 실행 중인지 |

## 참고

- `APP_DOMAIN`은 화면 빌드에 들어갑니다. 도메인을 바꾸면 `docker compose up -d --build` 로 다시 빌드해야 합니다.
- `OFFICE_ADMIN_PASSWORD` / `OFFICE_USER_PASSWORD`는 계정이 **처음 생성될 때만** 쓰입니다. 이후 `.env`를 바꿔도 기존 비밀번호는 바뀌지 않습니다.
- 회의 오디오는 저장하지 않습니다. 운영 전 README의 회의 '보안 경계' 항목(입장 토큰 폐기 등)을 확인하세요.
