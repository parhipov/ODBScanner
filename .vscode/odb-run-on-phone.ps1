# Собрать, поставить и запустить ODB Scanner на подключённом телефоне.
#
#   .\odb-run-on-phone.ps1                 # первый физический телефон (эмуляторы пропускаются)
#   .\odb-run-on-phone.ps1 -Serial XXXX    # конкретное устройство из `adb devices`
param(
    [string]$Serial = ""
)

$ErrorActionPreference = 'Stop'

$env:ANDROID_HOME = "E:\Android\Sdk"
$env:ANDROID_SDK_ROOT = "E:\Android\Sdk"
$adb = "E:\Android\Sdk\platform-tools\adb.exe"

Set-Location (Join-Path $PSScriptRoot "..")

& $adb start-server | Out-Null
if (-not $Serial) {
    $devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] }
    $Serial = $devices | Where-Object { $_ -notlike "emulator-*" } | Select-Object -First 1
    if (-not $Serial) { $Serial = $devices | Select-Object -First 1 }
    if (-not $Serial) {
        Write-Host "Нет подключённых устройств (adb devices пуст). Включите отладку по USB." -ForegroundColor Red
        exit 1
    }
}
Write-Host "Устройство: $Serial"
$env:ANDROID_SERIAL = $Serial

& ".\gradlew.bat" --no-daemon installDebug
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $adb -s $Serial shell am start -n com.odbscanner/.MainActivity
exit $LASTEXITCODE
