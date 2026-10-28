# on-sil 설치: JDK와 LiveKit 서버를 .tools 안에 내려받고 앱을 빌드합니다.
. (Join-Path $PSScriptRoot 'common.ps1')

# 1) 포터블 JDK 21
if (-not (Test-Path -LiteralPath $JavaExe)) {
    $zip = Join-Path $DlDir 'jdk.zip'
    if (-not (Test-Path -LiteralPath $zip)) { Invoke-Download $JdkUrl $zip }
    $hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLower()
    if ($hash -ne $JdkSha256) { Remove-Item -LiteralPath $zip; throw "JDK 체크섬 불일치: $hash" }
    Write-Step 'JDK 압축 해제'
    $tmp = Join-Path $ToolsDir 'jdk-extract'
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force }
    Expand-Zip $zip $tmp
    $inner = Get-ChildItem -LiteralPath $tmp -Directory | Select-Object -First 1
    if (Test-Path -LiteralPath $JdkDir) { Remove-Item -LiteralPath $JdkDir -Recurse -Force }
    Move-Item -LiteralPath $inner.FullName -Destination $JdkDir
    Remove-Item -LiteralPath $tmp -Recurse -Force
} else { Write-Step 'JDK 이미 설치됨' }

# 2) LiveKit 서버
if (-not (Test-Path -LiteralPath $LkExe)) {
    $zip = Join-Path $DlDir 'livekit.zip'
    if (-not (Test-Path -LiteralPath $zip)) { Invoke-Download $LkUrl $zip }
    Write-Step 'LiveKit 압축 해제'
    if (Test-Path -LiteralPath $LkDir) { Remove-Item -LiteralPath $LkDir -Recurse -Force }
    Expand-Zip $zip $LkDir
} else { Write-Step 'LiveKit 이미 설치됨' }

& $JavaExe -XX:-UsePerfData -version
if ($LASTEXITCODE -ne 0) { throw 'JDK 실행 실패' }

# 3) 앱 빌드 (Maven 본체와 라이브러리는 .m2 안에 받아집니다)
Write-Step '앱 빌드 (처음에는 몇 분 걸릴 수 있습니다)'
Invoke-Maven @('-q', '-DskipTests', 'package')
Write-Step '설치 완료. start.cmd 로 실행하세요.'
