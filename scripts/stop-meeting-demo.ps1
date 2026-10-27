$root=Split-Path $PSScriptRoot -Parent
$state=Join-Path $root 'backend\data\meeting-demo-runtime'
foreach($name in 'web','api','media'){
  $file=Join-Path $state "$name.pid"
  if(-not (Test-Path $file)){continue}
  $processId=[int](Get-Content $file)
  $proc=Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
  # Only stop processes whose executable/command points to this workspace demo.
  if($proc -and ($proc.CommandLine.Contains($root)) -and ($proc.CommandLine -match 'vite.js|cen-office-backend-0.2.0.jar|meeting-demo-runtime')){
    Stop-Process -Id $processId -Force
    Write-Host "$name 종료"
  }
}
