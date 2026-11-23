# CEN HUB 로컬 실행: 백엔드(8080) + 프론트엔드(5180)를 띄우고 브라우저를 엽니다.
$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$Host.UI.RawUI.WindowTitle = 'CEN HUB 로컬 실행'

function Test-Port([int]$port) { [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) }
function Status($url) {
  try { (Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop).StatusCode }
  catch { if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 } }
}

Write-Host '============================================'
Write-Host '  CEN HUB 로컬 실행'
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

# 회의(화상) 미디어 서버(LiveKit, 7880): 회의 기능에 필요. 실행 파일이 없으면 건너뛴다(scripts\setup-meeting-media.ps1 로 설치).
$livekit = Get-ChildItem "$root\.tools" -Directory -Filter 'livekit-*' -ErrorAction SilentlyContinue | Select-Object -First 1
$lkExe = if ($livekit) { Join-Path $livekit.FullName 'livekit-server.exe' } else { $null }
if (Test-Port 7880) { Write-Host '[회의 미디어] 이미 실행 중입니다 (7880)' }
elseif ($lkExe -and (Test-Path $lkExe)) {
  $lkProps = "$root\backend\.meeting-local.properties"
  if (-not (Test-Path $lkProps)) {
    function New-Hex { $b = New-Object byte[] 24; $r = [System.Security.Cryptography.RandomNumberGenerator]::Create(); try { $r.GetBytes($b) } finally { $r.Dispose() }; ([BitConverter]::ToString($b)).Replace('-', '').ToLower() }
    @("livekit.api-key=cen$((New-Hex).Substring(0,12))", "livekit.api-secret=$(New-Hex)", 'livekit.url=ws://localhost:7880', 'livekit.api-url=http://127.0.0.1:7880') | Set-Content $lkProps -Encoding ascii
  }
  $lk = @{}; Get-Content $lkProps | ForEach-Object { $k, $v = $_ -split '=', 2; if ($k) { $lk[$k] = $v } }
  $lkDir = "$root\backend\data\meeting-demo-runtime"; New-Item -ItemType Directory -Force -Path $lkDir | Out-Null
  @"
port: 7880
bind_addresses: ["127.0.0.1"]
rtc:
  tcp_port: 7881
  udp_port: 7882
  use_external_ip: false
  node_ip: 127.0.0.1
  enable_loopback_candidate: true
keys:
  $($lk['livekit.api-key']): $($lk['livekit.api-secret'])
logging:
  level: warn
"@ | Set-Content "$lkDir\livekit.yaml" -Encoding ascii
  Write-Host '[회의 미디어] 시작합니다 (7880)'
  Start-Process $lkExe -ArgumentList '--config', ('"' + "$lkDir\livekit.yaml" + '"') -WorkingDirectory $root -WindowStyle Hidden
} else { Write-Host '[회의 미디어] LiveKit 이 설치되어 있지 않아 건너뜁니다 (scripts\setup-meeting-media.ps1 실행 후 사용 가능)' }

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
