# on-sil 종료: start 가 켠 두 서버를 끕니다.
. (Join-Path $PSScriptRoot 'common.ps1')

foreach ($name in @('app', 'livekit')) {
    $procId = Get-RunningPid $name
    if ($procId) {
        Write-Step "$name 종료 (PID $procId)"
        Stop-Process -Id $procId -Force
        Wait-Process -Id $procId -Timeout 15 -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath (Join-Path $RunDir "$name.pid") -Force -ErrorAction SilentlyContinue
    } else {
        Write-Step "$name 실행 중 아님"
    }
}
Write-Step '종료 완료'
