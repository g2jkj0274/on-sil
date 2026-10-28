# on-sil 공통 설정: 모든 경로와 환경 변수는 이 프로세스 안에서만 바뀝니다 (시스템/사용자 환경 변수는 건드리지 않음).
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root      = Split-Path -Parent $PSScriptRoot
$ToolsDir  = Join-Path $Root '.tools'
$DlDir     = Join-Path $ToolsDir 'dl'
$JdkDir    = Join-Path $ToolsDir 'jdk'
$LkDir     = Join-Path $ToolsDir 'livekit'
$M2Dir     = Join-Path $Root '.m2'
$DataDir   = Join-Path $Root 'data'
$TmpDir    = Join-Path $DataDir 'tmp'
$LogDir    = Join-Path $DataDir 'logs'
$RunDir    = Join-Path $DataDir 'run'
$AppDir    = Join-Path $Root 'app'
$JarPath   = Join-Path $AppDir 'target\on-sil.jar'
$JavaExe   = Join-Path $JdkDir 'bin\java.exe'
$LkExe     = Join-Path $LkDir 'livekit-server.exe'

# 고정 버전 (setup 시 내려받는 도구)
$JdkUrl    = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip'
$JdkSha256 = 'f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e'
$LkUrl     = 'https://github.com/livekit/livekit/releases/download/v1.13.11/livekit_1.13.11_windows_amd64.zip'

foreach ($d in @($ToolsDir, $DlDir, $M2Dir, $DataDir, $TmpDir, $LogDir, $RunDir)) {
    if (-not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Path $d | Out-Null }
}

# 이 프로세스(와 자식 프로세스)에만 적용되는 환경 변수
$env:JAVA_HOME       = $JdkDir
$env:MAVEN_USER_HOME = $M2Dir
$env:TEMP            = $TmpDir
$env:TMP             = $TmpDir
$env:PATH            = (Join-Path $JdkDir 'bin') + ';' + $env:PATH
$env:MAVEN_OPTS      = '-XX:-UsePerfData'

function Write-Step($msg) { Write-Host "[on-sil] $msg" -ForegroundColor Cyan }

function Invoke-Download($url, $dest) {
    Write-Step "다운로드: $url"
    $part = "$dest.part"
    $wc = New-Object System.Net.WebClient
    try { $wc.DownloadFile($url, $part) } finally { $wc.Dispose() }
    Move-Item -LiteralPath $part -Destination $dest -Force
}

function Expand-Zip($zip, $dest) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $dest)
}

function Get-RunningPid($name) {
    $file = Join-Path $RunDir "$name.pid"
    if (-not (Test-Path -LiteralPath $file)) { return $null }
    $procId = [int](Get-Content -LiteralPath $file -Raw).Trim()
    $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if ($p) { return $procId }
    Remove-Item -LiteralPath $file -Force
    return $null
}

function Invoke-Maven([string[]]$mvnArgs) {
    Push-Location -LiteralPath $AppDir
    try {
        & (Join-Path $AppDir 'mvnw.cmd') @mvnArgs
        if ($LASTEXITCODE -ne 0) { throw "Maven 빌드 실패 (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
}
