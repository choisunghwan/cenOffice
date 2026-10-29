# scripts

| 파일 | 용도 |
|---|---|
| `../start-local.bat` → `start-local.ps1` | **기본 실행.** 백엔드(8080)와 프론트엔드(5180)를 띄우고 브라우저를 엽니다. |
| `stop-local.ps1` (`../stop-local.bat`) | 위 두 서버를 종료합니다. |
| `run-backend.ps1` | 백엔드만 실행합니다(`start-local.ps1`이 내부에서 사용). |
| `setup-postgres.ps1` | 로컬 PostgreSQL 준비. |
| `setup-mail.ps1` | 메일 발송 설정. |
| `setup-meeting-media.ps1` | 화상회의용 LiveKit 서버 내려받기. |
| `start-meeting-demo.ps1` / `stop-meeting-demo.ps1` | 화상회의 시연 전용 실행·종료(별도 포트 5174). `-WithAi`로 회의 AI '센'을 켭니다. |
| `test-meeting-*.cjs`, `check-meeting-tts.cjs` | 브라우저 자동 시연·검증 스크립트(Playwright 필요). |
