# CEN OFFICE 백엔드 실행 (Windows PowerShell)
# - 프로젝트 내 .tools 의 JDK 21 / Maven 이 있으면 사용, 없으면 시스템 java/mvn 사용
# - OFFICE_ADMIN_PASSWORD / OFFICE_USER_PASSWORD 가 없으면 최초 1회 임의 생성해 backend\.local-credentials.txt 에 저장(git 제외)
$root = Split-Path $PSScriptRoot -Parent
$jdk = Get-ChildItem "$root\.tools" -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue | Select-Object -First 1
$mvn = Get-ChildItem "$root\.tools" -Directory -Filter "apache-maven*" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName; $env:Path = "$($jdk.FullName)\bin;$env:Path" }
$mvnCmd = if ($mvn) { "$($mvn.FullName)\bin\mvn.cmd" } else { "mvn" }

$cred = "$root\backend\.local-credentials.txt"
if (-not ($env:OFFICE_ADMIN_PASSWORD -and $env:OFFICE_USER_PASSWORD)) {
  if (-not (Test-Path $cred)) {
    function New-Pw { -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 14 | ForEach-Object { [char]$_ }) }
    "OFFICE_ADMIN_PASSWORD=$(New-Pw)`nOFFICE_USER_PASSWORD=$(New-Pw)" | Set-Content $cred -Encoding ascii
  }
  Get-Content $cred | ForEach-Object { $k, $v = $_ -split '=', 2; Set-Item "env:$k" $v }
  Write-Host "계정 정보: $cred  (admin / user)"
}
# 개발용: 환경변수/파일의 비밀번호를 기존 계정에도 반영 (운영 환경에서는 사용 금지)
$env:OFFICE_SYNC_PASSWORDS = "true"
# 개발용: AI 모델 실행 허용 (입력이 app.llm 의 LLM 서비스로 전송됨. 운영 적용 전 보안 승인 필요)
if (-not $env:HUB_AI_ENGINE) { $env:HUB_AI_ENGINE = "true" }
Set-Location "$root\backend"
& $mvnCmd spring-boot:run
