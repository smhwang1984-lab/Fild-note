$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$jbr = "C:\Program Files\Android\Android Studio\jbr"
$buildTools = "C:\Users\SumH\AppData\Local\Android\Sdk\build-tools\36.0.0"
$aapt = Join-Path $buildTools "aapt.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$expectedSha1 = "27:D0:54:B7:A8:0D:CC:3F:C8:C6:E7:A9:E6:6E:9C:5E:20:F6:CB:D5"

if (-not (Test-Path (Join-Path $jbr "bin\java.exe"))) {
    throw "Android Studio JBR was not found: $jbr"
}

if (-not (Test-Path $aapt)) {
    throw "aapt was not found: $aapt"
}

if (-not (Test-Path $apksigner)) {
    throw "apksigner was not found: $apksigner"
}

function Normalize-Fingerprint([string]$value) {
    return (($value -replace '[^0-9A-Fa-f]', '').ToUpperInvariant())
}

$gradleFile = Join-Path $projectRoot "app\build.gradle.kts"
$gradle = Get-Content -Raw $gradleFile
$expectedPackage = [regex]::Match($gradle, 'applicationId\s*=\s*"([^"]+)"').Groups[1].Value
$expectedVersionCode = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$expectedVersionName = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value

if (-not $expectedPackage -or -not $expectedVersionCode -or -not $expectedVersionName) {
    throw "Could not read release package/version values from $gradleFile"
}

$env:JAVA_HOME = $jbr
Push-Location $projectRoot
try {
    & .\gradlew.bat assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle assembleRelease failed with exit code $LASTEXITCODE"
    }

    $apk = Join-Path $projectRoot "app\build\outputs\apk\release\app-release.apk"
    $badging = & $aapt dump badging $apk
    if ($LASTEXITCODE -ne 0) {
        throw "aapt dump badging failed with exit code $LASTEXITCODE"
    }

    $packageMatch = [regex]::Match($badging, "package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
    if (-not $packageMatch.Success) {
        throw "Could not parse APK package/version metadata"
    }

    $actualPackage = $packageMatch.Groups[1].Value
    $actualVersionCode = $packageMatch.Groups[2].Value
    $actualVersionName = $packageMatch.Groups[3].Value

    if ($actualPackage -ne $expectedPackage) {
        throw "Unexpected package name: $actualPackage (expected $expectedPackage)"
    }
    if ($actualVersionCode -ne $expectedVersionCode) {
        throw "Unexpected versionCode: $actualVersionCode (expected $expectedVersionCode)"
    }
    if ($actualVersionName -ne $expectedVersionName) {
        throw "Unexpected versionName: $actualVersionName (expected $expectedVersionName)"
    }

    $signerOutput = & cmd.exe /c "`"$apksigner`" verify --verbose --print-certs `"$apk`" 2>&1"
    if ($LASTEXITCODE -ne 0) {
        $signerOutput | Write-Host
        throw "APK signature verification failed with exit code $LASTEXITCODE"
    }

    $sha1Match = $signerOutput | Select-String -Pattern 'SHA-1 digest:\s*([0-9A-Fa-f:]+)'
    if (-not $sha1Match) {
        throw "Could not read signer SHA-1 from apksigner output"
    }

    $actualSha1 = $sha1Match.Matches[0].Groups[1].Value
    if ((Normalize-Fingerprint $actualSha1) -ne (Normalize-Fingerprint $expectedSha1)) {
        throw "Unexpected signing SHA-1: $actualSha1 (expected $expectedSha1)"
    }

    $releaseDir = Join-Path $projectRoot "release\v$expectedVersionName"
    New-Item -ItemType Directory -Force $releaseDir | Out-Null
    $releaseApk = Join-Path $releaseDir "FieldNote-v$expectedVersionName.apk"
    Copy-Item -Force $apk $releaseApk
    # One-time bridge for devices still running 1.2.6, whose old updater only knows update.apk.
    # From 1.2.7 onward the app discovers FieldNote-vX.Y.Z.apk/fildnote-vX.Y.Z.apk directly.
    $legacyUpdateApk = Join-Path $releaseDir "update.apk"
    Copy-Item -Force $releaseApk $legacyUpdateApk

    $hash = Get-FileHash -Algorithm SHA256 $releaseApk
    $hashFile = Join-Path $releaseDir "FieldNote-v$expectedVersionName.apk.sha256"
    "$($hash.Hash.ToLowerInvariant())  FieldNote-v$expectedVersionName.apk" | Set-Content -Encoding ASCII $hashFile

    Write-Host "Release APK: $releaseApk"
    Write-Host "Legacy 1.2.6 bridge APK: $legacyUpdateApk"
    Write-Host "Optional SHA-256 file: $hashFile"
    Write-Host "Package: $actualPackage"
    Write-Host "Version: $actualVersionName ($actualVersionCode)"
    Write-Host "Signer SHA-1: $expectedSha1"
    Write-Host "SHA-256: $($hash.Hash.ToLowerInvariant())"
} finally {
    Pop-Location
}
