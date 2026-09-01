$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$jbr = "C:\Program Files\Android\Android Studio\jbr"
$apksigner = "C:\Users\SumH\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat"

if (-not (Test-Path (Join-Path $jbr "bin\java.exe"))) {
    throw "Android Studio JBR was not found: $jbr"
}

$env:JAVA_HOME = $jbr
Push-Location $projectRoot
try {
    & .\gradlew.bat assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle assembleRelease failed with exit code $LASTEXITCODE"
    }

    $apk = Join-Path $projectRoot "app\build\outputs\apk\release\app-release.apk"
    if (Test-Path $apksigner) {
        & $apksigner verify --verbose $apk
        if ($LASTEXITCODE -ne 0) {
            throw "APK signature verification failed with exit code $LASTEXITCODE"
        }
    }
    Write-Host "Release APK: $apk"
} finally {
    Pop-Location
}
