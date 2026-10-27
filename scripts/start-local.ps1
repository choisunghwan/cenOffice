# CEN OFFICE 로컬 실행: 백엔드(8080) + 프론트엔드(5180)를 띄우고 브라우저를 엽니다.
$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$Host.UI.RawUI.WindowTitle = 'CEN OFFICE 로컬 실행'

function Test-Port([int]$port) { [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) }
function Status($url) {
  try { (Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop).StatusCode }
  catch { if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 } }
}

Write-Host '============================================'
Write-Host '  CEN OFFICE 로컬 실행'
Write-Host '============================================'

if (Test-Port 8080) { Write-Host '[백엔드] 이미 실행 중입니다 (8080)' }
else {
  Write-Host '[백엔드] 시작합니다... 첫 실행은 1~2분 걸릴 수 있습니다.'
  Start-Process powershell -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-NoExit', '-File', "$root\scripts\run-backend.ps1"
}

if (Test-Port 5180) { Write-Host '[프론트엔드] 이미 실행 중입니다 (5180)' }
else {
  if (-not (Test-Path "$root\frontend\node_modules")) {
    Write-Host '[프론트엔드] 패키지를 설치합니다 (최초 1회)...'
    Push-Location "$root\frontend"; npm install; Pop-Location
  }
  Write-Host '[프론트엔드] 시작합니다...'
  Start-Process cmd -ArgumentList '/k', 'npm run dev' -WorkingDirectory "$root\frontend"
}

Write-Host "`n서버가 준비될 때까지 기다리는 중... (최대 약 3분)"
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
  $b = Status 'http://localhost:8080/api/health'   # 로그인 전이므로 401 이 정상
  $f = Status 'http://localhost:5180/'
  if (($b -eq 401 -or $b -eq 200) -and $f -eq 200) { $ready = $true; break }
  Start-Sleep 3
}

if ($ready) {
  Write-Host "`n준비 완료! 브라우저를 엽니다: http://localhost:5180" -ForegroundColor Green
  Start-Process 'http://localhost:5180'
  Write-Host "`n계정: user (일반) / admin (관리자)"
  Write-Host '  비밀번호는 backend\.local-credentials.txt 를 확인하세요.'
  Write-Host '종료하려면 stop-local.bat 을 실행하세요.'
} else {
  Write-Host "`n시간 안에 서버가 준비되지 않았습니다. 열린 백엔드/프론트엔드 창의 오류 메시지를 확인하세요." -ForegroundColor Red
}
