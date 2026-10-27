$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$dest=Join-Path $root '.tools\livekit-1.13.10'
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$base='https://github.com/livekit/livekit/releases/download/v1.13.10'
Invoke-WebRequest "$base/checksums.txt" -OutFile (Join-Path $dest 'checksums.txt')
Invoke-WebRequest "$base/livekit_1.13.10_windows_amd64.zip" -OutFile (Join-Path $dest 'livekit.zip')
$line=Get-Content (Join-Path $dest 'checksums.txt') | Where-Object {$_ -match 'livekit_1.13.10_windows_amd64.zip$'}
if(-not $line -or (Get-FileHash (Join-Path $dest 'livekit.zip') -Algorithm SHA256).Hash -ne ($line -split '\s+')[0]) {throw 'LiveKit SHA256 검증 실패'}
Expand-Archive -LiteralPath (Join-Path $dest 'livekit.zip') -DestinationPath $dest -Force
Write-Host 'LiveKit 공식 Windows 바이너리 설치 및 SHA256 검증 완료.'
