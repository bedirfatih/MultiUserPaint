# MultiUserPaint build script
# Usage (in project directory): .\build.ps1
# Produces:
#   dist\MultiUserPaint.jar       (client, fat JAR)
#   dist\MultiUserPaintServer.jar (server, fat JAR)

$ErrorActionPreference = "Stop"
$base  = $PSScriptRoot
$src   = "$base\src"
$lib   = "$base\lib"
$build = "$base\build\classes"
$dist  = "$base\dist"

# Locate javac / jar from JDK-21 or PATH
$jdk21 = "C:\Program Files\Java\jdk-21\bin"
if (Test-Path "$jdk21\javac.exe") {
    $javac = "$jdk21\javac.exe"
    $jar   = "$jdk21\jar.exe"
} else {
    $javac = "javac"
    $jar   = "jar"
}

Write-Host "==> Cleaning build artefacts..." -ForegroundColor Cyan
if (Test-Path "$base\build") { Remove-Item "$base\build" -Recurse -Force }
New-Item -ItemType Directory -Force -Path $build | Out-Null
New-Item -ItemType Directory -Force -Path $dist  | Out-Null

# Classpath = all JARs in lib/
$cp = (Get-ChildItem "$lib\*.jar" | ForEach-Object { "`"$($_.FullName)`"" }) -join ";"

Write-Host "==> Collecting source files..." -ForegroundColor Cyan
# JDK 21 reads @-files as UTF-8 without BOM; use WriteAllLines for BOM-free UTF-8
$sourceList = "$env:TEMP\mup_sources.txt"
$lines = Get-ChildItem $src -Recurse -Filter "*.java" |
    ForEach-Object { "`"$($_.FullName.Replace('\','\\'))`"" }
[System.IO.File]::WriteAllLines($sourceList, $lines, [System.Text.UTF8Encoding]::new($false))

Write-Host "==> Compiling..." -ForegroundColor Cyan
& $javac -encoding UTF-8 -source 8 -target 8 -cp $cp -d $build "@$sourceList"
if ($LASTEXITCODE -ne 0) { Write-Host "COMPILE FAILED" -ForegroundColor Red; exit 1 }
Write-Host "    OK" -ForegroundColor Green

function New-FatJar {
    param([string]$destJar, [string]$manifestFile)

    $tmpDir = "$base\build\fat_tmp"
    if (Test-Path $tmpDir) { Remove-Item $tmpDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

    # Unpack each dependency JAR into tmpDir (skip signature files)
    Push-Location $tmpDir
    foreach ($dep in (Get-ChildItem "$lib\*.jar")) {
        & $jar xf $dep.FullName
    }
    # Remove PKCS7 signature files that would break the merged JAR
    Get-ChildItem "META-INF" -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in @(".SF", ".DSA", ".RSA") } |
        Remove-Item -Force
    Pop-Location

    # Copy compiled classes on top (overwrites any duplicate class files from deps)
    Copy-Item "$build\*" $tmpDir -Recurse -Force

    # Pack into fat JAR
    Push-Location $tmpDir
    & $jar cfm $destJar $manifestFile .
    $rc = $LASTEXITCODE
    Pop-Location
    Remove-Item $tmpDir -Recurse -Force

    if ($rc -ne 0) { Write-Host "JAR build failed: $destJar" -ForegroundColor Red; exit 1 }
    Write-Host "    $destJar" -ForegroundColor Green
}

Write-Host "==> Building client JAR..." -ForegroundColor Cyan
New-FatJar -destJar "$dist\MultiUserPaint.jar" -manifestFile "$base\manifest.mf"

Write-Host "==> Building server JAR..." -ForegroundColor Cyan
New-FatJar -destJar "$dist\MultiUserPaintServer.jar" -manifestFile "$base\manifest-server.mf"

Write-Host ""
Write-Host "BUILD SUCCESSFUL" -ForegroundColor Green
Write-Host "  Client JAR : $dist\MultiUserPaint.jar"
Write-Host "  Server JAR : $dist\MultiUserPaintServer.jar"
