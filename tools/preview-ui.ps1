param(
    [string]$OutDir = ""
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$GameRoot = "D:\Software\GameApp\steam\steamapps\common\ProjectZomboid"
$Jar = Join-Path $GameRoot "projectzomboid.jar"
$ModJar = Join-Path $Root "Contents\mods\EmberRoutes\42\media\java\client\EmberRoutes.jar"
$Java = Join-Path $GameRoot "jre64\bin\java.exe"
if (!(Test-Path -LiteralPath $Java)) {
    $Java = Join-Path $GameRoot "jre\bin\java.exe"
}
if (!(Test-Path -LiteralPath $Java)) {
    $Java = "java"
}
$NativeRoot = Join-Path $GameRoot ".lwjgl\3.4.1-snapshot\x64"

if ([string]::IsNullOrEmpty($OutDir)) {
    $OutDir = Join-Path $Root "java-src\build\ui-preview"
}
$ClassesDir = Join-Path $OutDir "classes"
New-Item -ItemType Directory -Force $ClassesDir | Out-Null

$Sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot "ui-preview") -Filter "*.java" | Select-Object -ExpandProperty FullName)
javac -encoding UTF-8 --release 17 -cp "$ModJar;$Jar" -d $ClassesDir @Sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Push-Location $GameRoot
try {
    & $Java `
        "--enable-native-access=ALL-UNNAMED" `
        "-Djava.library.path=$GameRoot;$NativeRoot" `
        "-Dorg.lwjgl.librarypath=$NativeRoot" `
        -cp "$ClassesDir;$ModJar;$Jar" `
        PreviewModule1 $Root $OutDir
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
