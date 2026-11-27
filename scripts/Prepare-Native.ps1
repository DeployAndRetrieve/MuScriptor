# Build the native MuScriptor GGUF runtime for Android arm64-v8a.
# Requires a Windows host with Git, CMake, Ninja, Android SDK+NDK, C++ toolchain.
# The script does NOT download model weights; accept their license separately.
param([string]$AndroidSdk = "", [string]$NdkPath = "", [string]$CmakePath = "", [string]$NinjaPath = "")
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
# Resolve CMake and Ninja from the Android SDK when they are not on PATH.
# Android Studio/Gradle may install both under SDK/cmake/<version>/bin.
$cmakeRoot = Join-Path $AndroidSdk 'cmake'
$cmakeDirs = @()
if (Test-Path -LiteralPath $cmakeRoot -PathType Container) {
  $cmakeDirs = @(Get-ChildItem -LiteralPath $cmakeRoot -Directory |
    Sort-Object -Property @{ Expression = {
      try { [version]$_.Name } catch { [version]'0.0' }
    }; Descending = $true })
}
if (-not $CmakePath) {
  foreach ($dir in $cmakeDirs) {
    $candidate = Join-Path $dir.FullName 'bin\cmake.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
      $CmakePath = $candidate
      break
    }
  }
  if (-not $CmakePath) {
    $found = Get-Command cmake.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($found) { $CmakePath = $found.Source }
  }
}
if (-not $CmakePath -or -not (Test-Path -LiteralPath $CmakePath -PathType Leaf)) {
  throw ("CMake not found. Install CMake in Android SDK Manager > SDK Tools > " +
    "CMake, or pass -CmakePath to this script. SDK checked: " + $cmakeRoot)
}
$CmakePath = (Resolve-Path -LiteralPath $CmakePath).Path
$cmakeBin = Split-Path -Parent $CmakePath

if (-not $NinjaPath) {
  $localNinja = Join-Path $cmakeBin 'ninja.exe'
  if (Test-Path -LiteralPath $localNinja -PathType Leaf) {
    $NinjaPath = $localNinja
  }
  if (-not $NinjaPath) {
    foreach ($dir in $cmakeDirs) {
      $candidate = Join-Path $dir.FullName 'bin\ninja.exe'
      if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        $NinjaPath = $candidate
        break
      }
    }
  }
  if (-not $NinjaPath) {
    $found = Get-Command ninja.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($found) { $NinjaPath = $found.Source }
  }
}
if (-not $NinjaPath -or -not (Test-Path -LiteralPath $NinjaPath -PathType Leaf)) {
  throw ("Ninja not found. Install CMake with Ninja in Android SDK Manager > " +
    "SDK Tools, or pass -NinjaPath. SDK checked: " + $cmakeRoot)
}
$NinjaPath = (Resolve-Path -LiteralPath $NinjaPath).Path
# Let CMake find its bundled Ninja and helper tools without requiring a
# machine-wide PATH change. Both paths may contain spaces.
$env:PATH = $cmakeBin + ';' + (Split-Path -Parent $NinjaPath) + ';' + $env:PATH
Write-Host ("Using CMake: " + $CmakePath) -ForegroundColor Green
Write-Host ("Using Ninja: " + $NinjaPath) -ForegroundColor Green

$toolchain = Join-Path $NdkPath 'build\cmake\android.toolchain.cmake'
if (-not (Test-Path $toolchain)) { throw "Android NDK toolchain missing: $toolchain" }
New-Item -ItemType Directory -Force (Split-Path $vendor) | Out-Null
if (-not (Test-Path (Join-Path $vendor '.git'))) {
  Invoke-Checked 'git' @('clone', '--recurse-submodules', 'https://github.com/0xShug0/audio.cpp.git', $vendor)
}
Invoke-Checked 'git' @('-C', $vendor, 'fetch', '--depth', '1', 'origin', $pin)
Invoke-Checked 'git' @('-C', $vendor, 'checkout', '--detach', $pin)
Invoke-Checked 'git' @('-C', $vendor, 'submodule', 'update', '--init', '--recursive')
# SentencePiece unconditionally adds a -fmacro-prefix-map argument using
# CMAKE_SOURCE_DIR without shell-quoting. Ninja splits it at spaces, causing:
#   clang++: no such file or directory: 'Files'
#   clang++: invalid argument 'E:/Program' to -fmacro-prefix-map=
# Disable only this optional source-path-rewriting flag for a source path
# with whitespace. This does NOT disable SentencePiece or GGML features.
# Do not patch the whole toolchain, and leave other compiler flags intact.
if ($vendor -match '\s') {
  $spCmake = Join-Path $vendor 'external\sentencepiece\CMakeLists.txt'
  if (-not (Test-Path -LiteralPath $spCmake -PathType Leaf)) {
    throw "SentencePiece CMakeLists.txt not found: $spCmake"
  }
  $spLines = [System.IO.File]::ReadAllLines($spCmake, [System.Text.Encoding]::UTF8)
  $badPrefix = 'string(APPEND CMAKE_CXX_FLAGS " -fmacro-prefix-map='
  $patchMarker = '# MuScriptor: omit unquoted prefix-map when source path contains spaces'
  $hits = 0
  $changed = $false
  for ($i = 0; $i -lt $spLines.Length; $i++) {
    if ($spLines[$i].Contains($badPrefix)) {
      $spLines[$i] = '  ' + $patchMarker
      $hits++
      $changed = $true
    } elseif ($spLines[$i].Contains($patchMarker)) {
      $hits++
    }
  }
  if ($hits -ne 1) {
    throw ("Cannot safely patch SentencePiece prefix-map (matches: $hits). " +
      "Check upstream source: " + $spCmake)
  }
  if ($changed) {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($spCmake, $spLines, $utf8NoBom)
    Write-Host 'Patched SentencePiece CMake flags for source path with spaces.' -ForegroundColor Green
  } else {
    Write-Host 'SentencePiece path-with-spaces patch already applied.'
  }
}

$build = Join-Path $root 'native-build\android-arm64'
Invoke-Checked $CmakePath @('-S', $vendor, '-B', $build, '-G', 'Ninja',
  "-DCMAKE_MAKE_PROGRAM=$NinjaPath",
  "-DCMAKE_TOOLCHAIN_FILE=$toolchain", '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-26',
  '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_CXX_STANDARD=17',
  '-DAUDIOCPP_MODEL_SET=custom', '-DAUDIOCPP_MODELS=muscriptor',
  '-DAUDIOCPP_BUILD_C_API=ON', '-DAUDIOCPP_DEPLOYMENT_BUILD=ON',
  '-DENGINE_ENABLE_NATIVE_CPU=OFF', '-DENGINE_ENABLE_LLAMAFILE=OFF',
  '-DENGINE_ENABLE_OPENMP=OFF', '-DGGML_OPENMP=OFF', '-DENGINE_ENABLE_VULKAN=OFF',
  '-DENGINE_ENABLE_CUDA=OFF', '-DENGINE_ENABLE_METAL=OFF',
  '-DAUDIOCPP_BUILD_NATIVE_MODEL_MANAGER=OFF')
Invoke-Checked $CmakePath @('--build', $build, '--target', 'audiocpp', '--parallel', '4')
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
