param(
    [string]$Scene = "game",
    [string]$Mock = "",
    [string]$Out = "screenshots\$Scene.png",
    [string]$Device = "",
    [int]$DelaySeconds = 2,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$adb = "D:\_\3rd\android-sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $adb = "adb"
}

Push-Location $root
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat :app:assembleDebug
    }

    $adbArgs = @()
    if ($Device) {
        $adbArgs += @("-s", $Device)
    }

    & $adb @adbArgs install -r "app\build\outputs\apk\debug\app-debug.apk" | Out-Host

    $startArgs = @(
        "shell", "am", "start",
        "-n", "com.example.pdkandroid/.MainActivity",
        "-e", "scene", $Scene
    )
    if ($Mock) {
        $startArgs += @("-e", "mock", $Mock)
    }
    & $adb @adbArgs @startArgs | Out-Host

    Start-Sleep -Seconds $DelaySeconds
    $outPath = Join-Path $root $Out
    $devicePath = "/sdcard/pdk-scene-$Scene.png"
    New-Item -ItemType Directory -Force -Path (Split-Path $outPath) | Out-Null
    & $adb @adbArgs shell screencap -p $devicePath | Out-Host
    & $adb @adbArgs pull $devicePath $outPath | Out-Host
    & $adb @adbArgs shell rm $devicePath | Out-Host
    Get-Item $outPath | Select-Object FullName,Length,LastWriteTime
} finally {
    Pop-Location
}
