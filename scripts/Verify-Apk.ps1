# Verifies an assembled Android package contains BOTH the license-prepared GGUF and the native runtime.
param(
  [string]$Apk = ""
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Apk) { $Apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk' }
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APKが見つかりません: $Apk" }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $Apk).Path)
try {
    $required = @(
        'assets/models/muscriptor-small-f32.gguf',
        'assets/models/muscriptor-small-f32.gguf.sha256',
        'lib/arm64-v8a/libaudiocpp.so'
    )
    foreach ($name in $required) {
        $entry = $zip.GetEntry($name)
        if ($null -eq $entry) { throw "APKに必須ファイルがありません: $name" }
        if ($name.EndsWith('.gguf') -and $entry.Length -lt 40000000) {
            throw "APK内のGGUFが小さすぎます: $($entry.Length) bytes"
        }
        if ($name.EndsWith('libaudiocpp.so') -and $entry.Length -lt 4096) {
            throw "APK内の推論エンジンが小さすぎます: $($entry.Length) bytes"
        }
        Write-Host ("OK: {0} ({1:N0} bytes)" -f $name,$entry.Length) -ForegroundColor Green
    }
    Write-Host 'APK内容検証成功。実機での推論動作・ライセンス適合性は別途確認してください。' -ForegroundColor Green
} finally {
    $zip.Dispose()
}
