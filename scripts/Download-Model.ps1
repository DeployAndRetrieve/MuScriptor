# Download preconverted MuScriptor Small GGUF for a private, noncommercial APK.
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less UTF-8 as ANSI.
# Read the original model terms before setting -AcceptModelTerms.
[CmdletBinding()]
param(
    [switch]$AcceptModelTerms,
    [switch]$ReplaceExisting
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $AcceptModelTerms) {
    throw @'
Review the MuScriptor Small model terms:
https://huggingface.co/MuScriptor/muscriptor-small
Model weights: CC BY-NC 4.0. Ensure you have rights to transcribe the audio.
After reviewing and accepting the terms, run:
  .\scripts\Download-Model.ps1 -AcceptModelTerms
This script cannot accept any website terms on your behalf.
'@
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dir = Join-Path $root 'native-build\weights'
$target = Join-Path $dir 'muscriptor-small-f32.gguf'
$temp = "$target.download"
$source = 'https://huggingface.co/audio-cpp/audio.cpp-gguf/resolve/main/MuScriptor-Small-GGUF/muscriptor-small-f32.gguf'

# Pinned SHA-256 published with the preconverted audio.cpp GGUF.
$expected = '4bff6f1c45b445e144c176434fbe27614ad6c4686e03f99ee11b17e15163fba2'

function Assert-ModelFile([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "GGUF file not found: $path"
    }
    $file = Get-Item -LiteralPath $path
    if ($file.Length -lt 40000000) {
        throw "GGUF file too small: $($file.Length) bytes"
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "GGUF SHA-256 mismatch. Actual: $actual; expected: $expected"
    }
    Write-Host ("GGUF verified: {0:N0} bytes / SHA-256 {1}" -f $file.Length, $actual) -ForegroundColor Green
}

if (Test-Path -LiteralPath $target -PathType Leaf) {
    try {
        Assert-ModelFile $target
        Write-Host "Model already present and verified: $target" -ForegroundColor Green
        exit 0
    } catch {
        if (-not $ReplaceExisting) {
            throw "Existing model does not match the pinned SHA-256. Use -ReplaceExisting to replace it. Details: $($_.Exception.Message)"
        }
        Write-Warning "Replacing existing model with different SHA-256: $target"
    }
}

if (-not (Get-Command 'curl.exe' -ErrorAction SilentlyContinue)) {
    throw 'curl.exe not found. Install curl for Windows or place the GGUF file manually.'
}
New-Item -ItemType Directory -Path $dir -Force | Out-Null
if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
Write-Host "Downloading MuScriptor Small F32 GGUF (approximately 412 MB): $source"
Write-Host 'The original CC BY-NC 4.0 model license continues to apply.'

try {
    & curl.exe --fail --location --retry 3 --connect-timeout 30 --progress-bar --output $temp $source
    if ($LASTEXITCODE -ne 0) {
        throw "GGUF download failed; curl exit code: $LASTEXITCODE"
    }
    Assert-ModelFile $temp
    Move-Item -LiteralPath $temp -Destination $target -Force
    Write-Host "Model installed: $target" -ForegroundColor Green
    Write-Host 'Next: prepare libaudiocpp.so for Android ARM64, then build the APK.'
} finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
}
