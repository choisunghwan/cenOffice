# 이메일 알림용 SMTP 설정을 만들어 backend\.local-mail.txt 에 저장합니다. (git 제외)
# - Gmail 또는 네이버 중 하나를 고릅니다. 둘 다 "앱 비밀번호"가 필요합니다(일반 로그인 비밀번호는 동작하지 않음).
#   Gmail : Google 계정 > 보안 > 2단계 인증 켜기 > 앱 비밀번호 만들기 (16자리)
#   네이버: 메일 > 환경설정 > POP3/IMAP 설정 > SMTP 사용함 + 2단계 인증 후 애플리케이션 비밀번호 만들기
# - 비밀번호는 이 창에서만 입력하며 화면/채팅/저장소에 남지 않습니다.
# - 설정을 지우려면 backend\.local-mail.txt 를 삭제하세요(이메일 알림이 꺼지고 화면 알림만 동작).
param([string]$BaseUrl = 'http://localhost:5180')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

Write-Host '보내는 메일 계정 종류를 고르세요.'
Write-Host '  1) Gmail (구글)'
Write-Host '  2) 네이버'
$choice = Read-Host '번호'
$address = (Read-Host '보내는 메일 주소 (예: name@gmail.com 또는 name@naver.com)').Trim()
if ($address -notmatch '^[^@\s]+@[^@\s]+\.[^@\s]+$') { throw '메일 주소 형식이 올바르지 않습니다.' }
$secure = Read-Host '앱 비밀번호' -AsSecureString
$pw = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)) -replace '\s', ''
if (-not $pw) { throw '앱 비밀번호가 비어 있습니다.' }

switch ($choice) {
  '1' { $host_ = 'smtp.gmail.com'; $port = 587; $ssl = 'false'; $starttls = 'true'; $user = $address }   # Gmail: 587 + STARTTLS
  '2' { $host_ = 'smtp.naver.com'; $port = 465; $ssl = 'true'; $starttls = 'false'; $user = ($address -split '@')[0] }   # 네이버 안내: 465 + SSL, 로그인 ID 는 주소의 앞부분
  default { throw '1 또는 2 를 입력하세요.' }
}
$file = Join-Path $root 'backend\.local-mail.txt'
@("MAIL_HOST=$host_", "MAIL_PORT=$port", "MAIL_STARTTLS=$starttls", "MAIL_SSL=$ssl", "MAIL_USERNAME=$user", "MAIL_PASSWORD=$pw", "MAIL_FROM=$address", "APP_BASE_URL=$BaseUrl") | Set-Content $file -Encoding ascii
Write-Host "`n저장했습니다: $file (git 제외)" -ForegroundColor Green
Write-Host '백엔드를 다시 시작하면 적용됩니다. 알림(종 모양) > 이메일 알림 설정에서 주소를 저장하고 "시험 메일 보내기"로 확인하세요.'
