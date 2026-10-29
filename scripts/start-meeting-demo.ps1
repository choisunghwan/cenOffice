param([switch]$SkipBuild, [switch]$NoBrowser, [switch]$SyncPasswords, [switch]$WithAi)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$backend=Join-Path $root 'backend'
$state=Join-Path $backend 'data\meeting-demo-runtime'
New-Item -ItemType Directory -Force -Path $state | Out-Null
$livekit=Join-Path $root '.tools\livekit-1.13.10\livekit-server.exe'
if(-not (Test-Path $livekit)) { throw 'LiveKit이 없습니다. 먼저 scripts/setup-meeting-media.ps1을 실행하세요.' }
$jdk=Get-ChildItem (Join-Path $root '.tools') -Directory -Filter 'jdk-21*' | Select-Object -First 1
$maven=Get-ChildItem (Join-Path $root '.tools') -Directory -Filter 'apache-maven*' | Select-Object -First 1
if(-not $jdk -or -not $maven){throw '.tools에 JDK 21 / Maven이 필요합니다.'}
$java=Join-Path $jdk.FullName 'bin\java.exe'
function Secret { $b=New-Object byte[] 24; $rng=[System.Security.Cryptography.RandomNumberGenerator]::Create(); try{$rng.GetBytes($b)}finally{$rng.Dispose()}; return [BitConverter]::ToString($b).Replace('-','').ToLower() }
$properties=Join-Path $backend '.meeting-local.properties'
if(-not (Test-Path $properties)) {
  $key='cen'+(Secret).Substring(0,12);$secret=Secret
  @("livekit.api-key=$key","livekit.api-secret=$secret",'livekit.url=ws://localhost:7880','livekit.api-url=http://127.0.0.1:7880') | Set-Content $properties -Encoding ascii
}
$settings=@{};Get-Content $properties | ForEach-Object { $k,$v=$_ -split '=',2; if($k){$settings[$k]=$v} }
$config=Join-Path $state 'livekit.yaml'
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
  $($settings['livekit.api-key']): $($settings['livekit.api-secret'])
logging:
  level: warn
"@ | Set-Content $config -Encoding ascii
$credentials=Join-Path $backend '.local-credentials-meeting.txt'
if(-not (Test-Path $credentials)) { @("OFFICE_ADMIN_PASSWORD=$((Secret).Substring(0,12))","OFFICE_USER_PASSWORD=$((Secret).Substring(0,12))") | Set-Content $credentials -Encoding ascii }
Get-Content $credentials | ForEach-Object { $k,$v=$_ -split '=',2; Set-Item "env:$k" $v }
$env:LLM_PROVIDER='mock';$env:HUB_AI_ENGINE='false';$env:HUB_RUNTIME='none';$env:OFFICE_SYNC_PASSWORDS=if($SyncPasswords){'true'}else{'false'}
$env:MEETING_AI_ENABLED='false'
if($WithAi) {
  # Explicit opt-in only. Keep keys in the existing ignored local credentials file or environment.
  $aiCredentials=Join-Path $backend '.local-credentials.txt'
  if(Test-Path $aiCredentials) {
    Get-Content $aiCredentials | ForEach-Object {
      $k,$v=$_ -split '=',2
      if($k -in @('GEMINI_API_KEY','GEMINI_MODEL','MEETING_TTS_MODEL','MEETING_TTS_VOICE') -and -not (Test-Path "env:$k")) { Set-Item "env:$k" $v }
    }
  }
  if(-not $env:GEMINI_API_KEY){throw 'GEMINI_API_KEY를 환경변수 또는 backend/.local-credentials.txt에 설정하세요. 키를 채팅에 붙여 넣지 마세요.'}
  $env:LLM_PROVIDER='gemini';$env:MEETING_AI_ENABLED='true'
  Write-Host 'AI 센 사용: 질문과 동의한 참석자의 회의 기록이 Gemini로 전송됩니다. TTS는 답변 텍스트를 전송합니다.'
}
$env:CORS_ORIGINS='http://localhost:5174,http://127.0.0.1:5174'
if(-not $SkipBuild) {
  Push-Location $backend
  try {
    $boot=(Get-ChildItem (Join-Path $maven.FullName 'boot\plexus-classworlds-*.jar') | Select-Object -First 1).FullName
    & $java "-Dmaven.home=$($maven.FullName)" "-Dmaven.multiModuleProjectDirectory=$backend" "-Dclassworlds.conf=$($maven.FullName)\bin\m2.conf" -classpath $boot org.codehaus.plexus.classworlds.launcher.Launcher -q -DskipTests package
    if($LASTEXITCODE -ne 0){throw '백엔드 빌드 실패'}
  } finally {Pop-Location}
}
function Start-DemoProcess($name,$exe,$arguments,$directory,$port) {
  $pidPath=Join-Path $state "$name.pid"
  if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
    if(Test-Path $pidPath){$saved=Get-Content $pidPath; $p=Get-Process -Id $saved -ErrorAction SilentlyContinue; if($p -and $p.Path -eq $exe){if($WithAi -and $name -eq 'api'){throw '기존 회의 서버가 실행 중입니다. stop-meeting-demo.ps1로 종료한 뒤 -WithAi로 다시 실행하세요.'};Write-Host "$name 이미 실행 중";return}}
    throw "포트 $port 가 사용 중입니다. 다른 프로세스는 자동 종료하지 않습니다."
  }
  $p=Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $directory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $state "$name.out.log") -RedirectStandardError (Join-Path $state "$name.err.log")
  $p.Id | Set-Content $pidPath
}
Start-DemoProcess 'media' $livekit @('--config',('"'+$config+'"')) $root 7880
$jar=Join-Path $backend 'target\cen-office-backend-0.2.0.jar'
Start-DemoProcess 'api' $java @('-jar',('"'+$jar+'"'),'--server.port=8090','--server.address=127.0.0.1','--spring.datasource.url=jdbc:h2:file:./data/meeting-demo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH') $backend 8090
$env:VITE_API_URL='http://localhost:8090'
$node=(Get-Command node.exe).Source
$vite=Join-Path $root 'frontend\node_modules\vite\bin\vite.js'
Start-DemoProcess 'web' $node @(('"'+$vite+'"'),'--host','127.0.0.1','--port','5174','--strictPort') (Join-Path $root 'frontend') 5174
Write-Host "회의 시연: http://localhost:5174"
Write-Host "서로 다른 브라우저에서 admin / user 계정으로 로그인하세요. 비밀번호 파일: $credentials"
Write-Host "첫 시작에는 백엔드 준비에 약 30초가 걸립니다. 로그: $state"
if(-not $NoBrowser){Start-Process 'http://localhost:5174'}
