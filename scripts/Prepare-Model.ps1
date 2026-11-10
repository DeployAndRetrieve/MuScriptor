# Converts an authorized MuScriptor Small checkpoint to a local GGUF, on Windows.
# The converted model is embedded in PRIVATE APK builds, not committed to Git. Subject to CC BY-NC 4.0.
param([Parameter(Mandatory=$true)][string]$CheckpointDir)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$vendor=Join-Path $root 'third_party\audio.cpp'
if (-not (Test-Path (Join-Path $vendor '.git'))) {throw 'Run scripts/Prepare-Native.ps1 first'}
$source=(Resolve-Path $CheckpointDir).Path
foreach($file in @('model.safetensors','config.json')){
 if(-not (Test-Path (Join-Path $source $file))){throw "Missing $file in $source"}
}
function Invoke-Checked([string]$exe,[string[]]$argv){
 & $exe @argv
 if($LASTEXITCODE -ne 0){throw "$exe exited with code $LASTEXITCODE"}
}
$build=Join-Path $root 'native-build\host'
Invoke-Checked 'cmake' @('-S',$vendor,'-B',$build,
 '-DAUDIOCPP_MODEL_SET=custom','-DAUDIOCPP_MODELS=muscriptor',
 '-DENGINE_ENABLE_NATIVE_CPU=OFF','-DENGINE_ENABLE_LLAMAFILE=OFF',
 '-DENGINE_ENABLE_OPENMP=OFF','-DGGML_OPENMP=OFF')
Invoke-Checked 'cmake' @('--build',$build,'--config','Release','--target','audiocpp_gguf','--parallel','4')
$converter=Get-ChildItem $build -Filter 'audiocpp_gguf*' -Recurse -File |
 Where-Object { $_.Name -eq 'audiocpp_gguf.exe' -or $_.Name -eq 'audiocpp_gguf' } | Select-Object -First 1
if(-not $converter){throw 'audiocpp_gguf converter not found'}
$outDir=Join-Path $root 'native-build\weights'
New-Item -ItemType Directory -Force $outDir | Out-Null
$output=Join-Path $outDir 'muscriptor-small-f32.gguf'
Write-Host 'Requires Python packages: numpy, safetensors (install them first if missing)'
Invoke-Checked 'python' @((Join-Path $vendor 'tests\muscriptor\convert_gguf.py'),
 '--source',$source,'--output',$output,'--converter',$converter.FullName,'--overwrite')
Write-Host "Model converted: $output" -ForegroundColor Green
Write-Host 'The APK build now embeds this local GGUF automatically. Run .\gradlew.bat :app:assembleDebug after preparing the Android native runtime.' -ForegroundColor Green
