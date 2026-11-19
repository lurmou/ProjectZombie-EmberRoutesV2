$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$GameDir = "D:\Software\GameApp\steam\steamapps\common\ProjectZomboid"
$ZBJar   = "D:\Software\GameApp\steam\steamapps\workshop\content\108600\3619862853\mods\ZombieBuddy\libs\ZombieBuddy.jar"
$ModJar  = "$Root\Contents\mods\EmberRoutes\42\media\java\client\EmberRoutes.jar"

Write-Host "==> [1/4] Checking CN/EN translation parity..." -ForegroundColor Cyan
python "$Root\tools\check_i18n.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> [2/4] Checking layered architecture dependency boundaries..." -ForegroundColor Cyan
python "$Root\tools\check_arch.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> [3/4] Building EmberRoutes Java JAR & EmmyLua stubs..." -ForegroundColor Cyan
Push-Location "$Root\java-src"
try {
    .\gradlew.bat build --no-daemon
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}

Write-Host "==> [4/4] Running Kahlua2 + Java Module 1 contract verification..." -ForegroundColor Cyan
Push-Location $GameDir
try {
    java -ea -cp "$ModJar;$ZBJar;$GameDir\*" "$Root\tools\VerifyModule1.java" "$Root" "$GameDir"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}

Write-Host "==> EmberRoutes Module 1 build, i18n, architecture & Kahlua2 verification ALL PASSED." -ForegroundColor Green
