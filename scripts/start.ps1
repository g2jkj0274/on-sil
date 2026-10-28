# on-sil 실행: LiveKit 서버와 Spring Boot 서버를 백그라운드로 켭니다.
. (Join-Path $PSScriptRoot 'common.ps1')

if (-not (Test-Path -LiteralPath $JavaExe) -or -not (Test-Path -LiteralPath $LkExe)) {
    throw '먼저 setup.cmd 를 실행하세요.'
}

# 소스가 jar보다 새로우면 다시 빌드
$needBuild = -not (Test-Path -LiteralPath $JarPath)
if (-not $needBuild) {
    $jarTime = (Get-Item -LiteralPath $JarPath).LastWriteTimeUtc
    $newest = Get-ChildItem -LiteralPath (Join-Path $AppDir 'src') -Recurse -File |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    $pomTime = (Get-Item -LiteralPath (Join-Path $AppDir 'pom.xml')).LastWriteTimeUtc
    if (($newest -and $newest.LastWriteTimeUtc -gt $jarTime) -or $pomTime -gt $jarTime) { $needBuild = $true }
}
if ($needBuild -and -not (Get-RunningPid 'app')) {
    Write-Step '변경된 소스가 있어 앱을 빌드합니다'
    Invoke-Maven @('-q', '-DskipTests', 'package')
}

# LiveKit
if (Get-RunningPid 'livekit') { Write-Step 'LiveKit 이미 실행 중' }
else {
    Write-Step 'LiveKit 서버 시작 (127.0.0.1:7880)'
    $p = Start-Process -FilePath $LkExe -WorkingDirectory $Root -WindowStyle Hidden -PassThru `
        -ArgumentList @('--dev', '--config', 'scripts\livekit.yaml') `
        -RedirectStandardOutput (Join-Path $LogDir 'livekit.out.log') `
        -RedirectStandardError (Join-Path $LogDir 'livekit.log')
    Set-Content -LiteralPath (Join-Path $RunDir 'livekit.pid') -Value $p.Id
}

# Spring Boot (작업 폴더를 on-sil 로 두고 data\ 상대 경로를 씁니다)
if (Get-RunningPid 'app') { Write-Step 'Spring Boot 이미 실행 중' }
else {
    Write-Step 'Spring Boot 서버 시작 (127.0.0.1:8080)'
    $p = Start-Process -FilePath $JavaExe -WorkingDirectory $Root -WindowStyle Hidden -PassThru `
        -ArgumentList @('-XX:-UsePerfData', '-Djava.io.tmpdir=data\tmp', '-Dfile.encoding=UTF-8',
                        '-jar', 'app\target\on-sil.jar') `
        -RedirectStandardOutput (Join-Path $LogDir 'app.log') `
        -RedirectStandardError (Join-Path $LogDir 'app.err.log')
    Set-Content -LiteralPath (Join-Path $RunDir 'app.pid') -Value $p.Id
}

Write-Step '서버 준비를 기다리는 중...'
$ok = $false
for ($i = 0; $i -lt 90; $i++) {
    if (-not (Get-RunningPid 'app')) { break }
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri 'http://127.0.0.1:8080/login.html'
        if ($r.StatusCode -eq 200) { $ok = $true; break }
    } catch [System.Net.WebException] {
        # 401 등 HTTP 응답이 왔다면 서버는 떠 있는 것
        if ($_.Exception.Response) { $ok = $true; break }
    } catch { }
    Start-Sleep -Seconds 1
}
if ($ok) {
    Write-Step '준비 완료: http://localhost:8080  (종료: stop.cmd, 로그: data\logs)'
} else {
    Write-Host '[on-sil] Spring Boot 서버가 시작되지 않았습니다. data\logs\app.log 를 확인하세요.' -ForegroundColor Red
    exit 1
}
