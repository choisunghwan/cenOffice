# CEN OFFICE 로컬 서버 종료 (포트 8080, 5173 을 쓰는 프로세스)
foreach ($port in 8080, 5173) {
  Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | ForEach-Object {
    try { Stop-Process -Id $_.OwningProcess -Force -ErrorAction Stop; Write-Host "포트 $port 프로세스 $($_.OwningProcess) 종료" }
    catch { Write-Host "포트 $port 프로세스 $($_.OwningProcess) 종료 실패: $($_.Exception.Message)" }
  }
}
Write-Host '완료. 열려 있는 백엔드/프론트엔드 창은 직접 닫아도 됩니다.'
