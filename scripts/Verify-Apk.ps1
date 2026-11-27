# Verify that the built Android APK includes the MuScriptor model and ARM64 runtime.
# ASCII-only source for compatibility with Windows PowerShell 5.1 (BOM-less UTF-8).
[CmdletBinding()]
param(
    [string]$Apk = ""
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Apk) {
    $Apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
}
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw "APK file not found: $Apk. Build with .\gradlew.bat :app:assembleDebug first."
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Apk).Path)
try {
    $required = @(
        'assets/models/muscriptor-small-f32.gguf',
        'assets/models/muscriptor-small-f32.gguf.sha256',
        'lib/arm64-v8a/libaudiocpp.so'
    )
    foreach ($name in $required) {
        $entry = $zip.GetEntry($name)
        if ($null -eq $entry) {
            throw "Required file missing from APK: $name"
        }
        if ($name.EndsWith('.gguf') -and $entry.Length -lt 40000000) {
            throw "GGUF file in APK is too small: $($entry.Length) bytes"
        }
        if ($name.EndsWith('libaudiocpp.so') -and $entry.Length -lt 4096) {
            throw "Native runtime file in APK is too small: $($entry.Length) bytes"
        }
        Write-Host ("OK: {0} ({1:N0} bytes)" -f $name, $entry.Length) -ForegroundColor Green
    }
    Write-Host 'APK content check passed. Native inference and model license compliance still require separate validation.' -ForegroundColor Green
} finally {
    $zip.Dispose()
}
