# Build the native MuScriptor GGUF runtime for Android arm64-v8a.
# Requires a Windows host with Git, CMake, Ninja, Android SDK+NDK, C++ toolchain.
# The script does NOT download model weights; accept their license separately.
param([string]$AndroidSdk = "", [string]$NdkPath = "")
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$vendor = Join-Path $root 'third_party\audio.cpp'
$pin = 'e74e0aaea1a9ed0d3dde4752c73670068f9c1ea4'
function Invoke-Checked([string]$exe, [string[]]$argv) {
  Write-Host ("`n> " + $exe + " " + ($argv -join ' '))
  & $exe @argv
  if ($LASTEXITCODE -ne 0) { throw "$exe exited with code $LASTEXITCODE" }
}
if (-not $AndroidSdk) {
  if ($env:ANDROID_HOME) { $AndroidSdk = $env:ANDROID_HOME }
  elseif ($env:ANDROID_SDK_ROOT) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
  else { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
}
if (-not $NdkPath) {
  $ndkDir = Join-Path $AndroidSdk 'ndk'
  if (-not (Test-Path $ndkDir)) { throw "NDK is not installed: $ndkDir" }
  $ndks = @(Get-ChildItem $ndkDir -Directory | Sort-Object -Property Name -Descending)
  if ($ndks.Count -eq 0) { throw 'No NDK versions found' }
  $NdkPath = $ndks[0].FullName
}
$toolchain = Join-Path $NdkPath 'build\cmake\android.toolchain.cmake'
if (-not (Test-Path $toolchain)) { throw "Android NDK toolchain missing: $toolchain" }
New-Item -ItemType Directory -Force (Split-Path $vendor) | Out-Null
if (-not (Test-Path (Join-Path $vendor '.git'))) {
  Invoke-Checked 'git' @('clone', '--recurse-submodules', 'https://github.com/0xShug0/audio.cpp.git', $vendor)
}
Invoke-Checked 'git' @('-C', $vendor, 'fetch', '--depth', '1', 'origin', $pin)
Invoke-Checked 'git' @('-C', $vendor, 'checkout', '--detach', $pin)
Invoke-Checked 'git' @('-C', $vendor, 'submodule', 'update', '--init', '--recursive')
$build = Join-Path $root 'native-build\android-arm64'
Invoke-Checked 'cmake' @('-S', $vendor, '-B', $build, '-G', 'Ninja',
  "-DCMAKE_TOOLCHAIN_FILE=$toolchain", '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-26',
  '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_CXX_STANDARD=17',
  '-DAUDIOCPP_MODEL_SET=custom', '-DAUDIOCPP_MODELS=muscriptor',
  '-DAUDIOCPP_BUILD_C_API=ON', '-DAUDIOCPP_DEPLOYMENT_BUILD=ON',
  '-DENGINE_ENABLE_NATIVE_CPU=OFF', '-DENGINE_ENABLE_LLAMAFILE=OFF',
  '-DENGINE_ENABLE_OPENMP=OFF', '-DGGML_OPENMP=OFF', '-DENGINE_ENABLE_VULKAN=OFF',
  '-DENGINE_ENABLE_CUDA=OFF', '-DENGINE_ENABLE_METAL=OFF',
  '-DAUDIOCPP_BUILD_NATIVE_MODEL_MANAGER=OFF')
Invoke-Checked 'cmake' @('--build', $build, '--target', 'audiocpp', '--parallel', '4')
$library = Get-ChildItem $build -Filter 'libaudiocpp.so' -Recurse | Select-Object -First 1
if (-not $library) { throw 'Build succeeded but libaudiocpp.so was not located' }
$dest=Join-Path $root 'app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force $dest | Out-Null
# Package every .so produced by this target (ggml may be linked dynamically).
Get-ChildItem $build -Filter '*.so' -Recurse -File | ForEach-Object {
  Copy-Item -Force $_.FullName (Join-Path $dest $_.Name)
}
Copy-Item -Force $library.FullName (Join-Path $dest 'libaudiocpp.so')
Write-Host "Native engine installed: $dest\libaudiocpp.so" -ForegroundColor Green
Write-Host 'Inspect imported libraries with llvm-readelf -d. Dependencies not built into the tree still need packaging.' -ForegroundColor Yellow
Write-Host 'Build the Android app with Gradle from the project root.'
