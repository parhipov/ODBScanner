$ErrorActionPreference = 'Stop'

$adb = "E:\Android\Sdk\platform-tools\adb.exe"

& $adb start-server | Out-Null
Write-Host "Following logcat tags ODB (обмен с адаптером) and AndroidRuntime (падения). Press Ctrl+C to stop."
& $adb logcat -v time ODB:D AndroidRuntime:E *:S
