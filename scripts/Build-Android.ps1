$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    if (Test-Path '.\gradle\wrapper\gradle-wrapper.jar') {
        & '.\gradlew.bat' '--no-daemon' ':app:assembleDebug'
    } elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
        & 'gradle' '--no-daemon' ':app:assembleDebug'
    } else {
        throw 'Gradle 8.13 not found. Open this project in Android Studio or install a verified Gradle wrapper.'
    }
    if ($LASTEXITCODE -ne 0) {throw "Gradle failed: $LASTEXITCODE"}
    & (Join-Path $PSScriptRoot 'Verify-Apk.ps1')
    if ($LASTEXITCODE -ne 0) {throw "APK verification failed: $LASTEXITCODE"}
} finally { Pop-Location }
