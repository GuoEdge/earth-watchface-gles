# 快速构建并安装到已连接的 Wear OS 手表（WiFi 调试需先 adb connect）
$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$JavaHome = "D:\AndroidStudio\jbr"
$SdkTools = "D:\AndroidSDK\platform-tools"
$Apk = Join-Path $Root "wear\build\outputs\apk\debug\wear-debug.apk"

$env:JAVA_HOME = $JavaHome
$env:PATH = "$SdkTools;$JavaHome\bin;$env:PATH"

Write-Host "=== ADB 设备 ===" -ForegroundColor Cyan
adb devices -l
$devices = @(adb devices | Select-String "device$" | Where-Object { $_ -notmatch "List of devices" })
if ($devices.Count -eq 0) {
    Write-Host ""
    Write-Host "未检测到手表。请先配对 WiFi 调试：" -ForegroundColor Yellow
    Write-Host "  手表: 设置 -> 开发者选项 -> 无线调试 -> 配对设备"
    Write-Host "  电脑: adb pair <IP>:<配对端口>  (输入手表显示的配对码)"
    Write-Host "        adb connect <IP>:<连接端口>"
    Write-Host ""
    exit 1
}

if (-not (Test-Path $Apk)) {
    Write-Host "=== 构建 Debug APK（使用国内镜像）===" -ForegroundColor Cyan
    Push-Location $Root
    & .\gradlew.bat :wear:assembleDebug --parallel --build-cache
    if ($LASTEXITCODE -ne 0) { Pop-Location; exit $LASTEXITCODE }
    Pop-Location
} else {
    Write-Host "=== 使用已有 APK（跳过编译）===" -ForegroundColor Green
    Write-Host $Apk
}

Write-Host "=== 安装到手表 ===" -ForegroundColor Cyan
adb install -r $Apk
if ($LASTEXITCODE -eq 0) {
    Write-Host "安装成功。在手表表盘列表中选择 Earth Live。" -ForegroundColor Green
}
