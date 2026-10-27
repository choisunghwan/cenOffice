# CEN OFFICE 용 PostgreSQL 데이터베이스/계정을 만들고, 접속 정보를 backend\.local-db.txt 에 저장합니다.
# - postgres 관리자 비밀번호는 실행할 때 이 창에서만 입력합니다(화면/파일/저장소에 남기지 않음).
# - backend\.local-db.txt 는 git 에서 제외됩니다. run-backend.ps1 이 이 파일이 있으면 PostgreSQL 로 접속합니다.
# - 파일을 지우면 다시 H2(로컬 파일 DB)로 돌아갑니다.
param([int]$Port = 5433, [string]$Database = 'cenoffice', [string]$AppUser = 'cenoffice')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$bin = Get-ChildItem 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue | Sort-Object { [int]$_.Name } -Descending | ForEach-Object { Join-Path $_.FullName 'bin\psql.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $bin) { throw 'psql 을 찾을 수 없습니다. PostgreSQL 이 설치되어 있어야 합니다.' }
Write-Host "psql: $bin  /  포트: $Port"

$secure = Read-Host 'postgres 관리자 비밀번호' -AsSecureString
$admin = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
$env:PGPASSWORD = $admin
function Psql([string]$db, [string]$sql) { & $bin -h localhost -p $Port -U postgres -d $db -v ON_ERROR_STOP=1 -tA -c $sql }

$version = Psql 'postgres' 'show server_version'
Write-Host "PostgreSQL 버전: $version"
if ([int]($version.Split('.')[0]) -lt 13) { Write-Warning '이 서버는 13 미만입니다. 16 서버(보통 포트 5433)를 권장합니다. 계속하려면 Enter, 중단하려면 Ctrl+C'; Read-Host | Out-Null }

$bytes = New-Object byte[] 18; $rng = [Security.Cryptography.RandomNumberGenerator]::Create(); $rng.GetBytes($bytes); $rng.Dispose()
$appPw = [BitConverter]::ToString($bytes).Replace('-', '').ToLower()

if ((Psql 'postgres' "select count(*) from pg_roles where rolname='$AppUser'") -eq '0') { Psql 'postgres' "create role $AppUser login password '$appPw'" | Out-Null; $created = $true }
else { Psql 'postgres' "alter role $AppUser password '$appPw'" | Out-Null; $created = $false }
if ((Psql 'postgres' "select count(*) from pg_database where datname='$Database'") -eq '0') { Psql 'postgres' "create database $Database owner $AppUser encoding 'UTF8'" | Out-Null; Write-Host "데이터베이스 '$Database' 를 만들었습니다." }
else { Write-Host "데이터베이스 '$Database' 는 이미 있습니다. 소유자를 $AppUser 로 맞춥니다."; Psql 'postgres' "alter database $Database owner to $AppUser" | Out-Null }
Remove-Item Env:PGPASSWORD

$file = Join-Path $root 'backend\.local-db.txt'
@("DB_URL=jdbc:postgresql://localhost:$Port/$Database", "DB_USER=$AppUser", "DB_PASSWORD=$appPw") | Set-Content $file -Encoding ascii
Write-Host "`n완료. 접속 정보를 저장했습니다: $file (git 제외)" -ForegroundColor Green
Write-Host '이제 백엔드를 다시 시작하면 PostgreSQL 로 접속합니다. (H2 로 돌아가려면 위 파일을 삭제)'
