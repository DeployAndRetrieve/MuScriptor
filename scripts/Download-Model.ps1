# Obtain a publicly hosted, preconverted MuScriptor Small GGUF for a PRIVATE, noncommercial build.
# Original model license and rights restrictions still apply. Requires explicit user acceptance.
[CmdletBinding()]
param(
    [switch]$AcceptModelTerms,
    [switch]$ReplaceExisting
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $AcceptModelTerms) {
    throw @'
MuScriptorモデルの利用条件を確認してください:
https://huggingface.co/MuScriptor/muscriptor-small
重みは CC BY-NC 4.0、音楽の採譜には必要な権利が求められます。
確認・同意後に次を実行してください:
  .\scripts\Download-Model.ps1 -AcceptModelTerms
このスクリプトが公式モデルのアクセス承認を代行することはありません。
'@
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dir = Join-Path $root 'native-build\weights'
$target = Join-Path $dir 'muscriptor-small-f32.gguf'
$temp = "$target.download"
$source = 'https://huggingface.co/audio-cpp/audio.cpp-gguf/resolve/main/MuScriptor-Small-GGUF/muscriptor-small-f32.gguf'
# Published SHA-256 on the audio-cpp Hugging Face file page. Pin the content,
# rather than trusting an unversioned web download to remain unchanged.
$expected = '4bff6f1c45b445e144c176434fbe27614ad6c4686e03f99ee11b17e15163fba2'

function Assert-ModelFile([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "GGUFファイルが見つかりません: $path"
    }
    $file = Get-Item -LiteralPath $path
    if ($file.Length -lt 40000000) {
        throw "ダウンロードしたGGUFが小さすぎます: $($file.Length) bytes"
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "GGUFのSHA-256不一致: $actual （期待値: $expected）"
    }
    Write-Host ("検証成功: {0:N0} bytes / SHA-256 {1}" -f $file.Length, $actual) -ForegroundColor Green
}

if (Test-Path -LiteralPath $target -PathType Leaf) {
    try {
        Assert-ModelFile $target
        Write-Host "モデルは既に準備済みです: $target" -ForegroundColor Green
        exit 0
    } catch {
        if (-not $ReplaceExisting) {
            throw "既存モデルのハッシュが公開GGUFと異なります。置換する場合は -ReplaceExisting を付けてください。詳細: $($_.Exception.Message)"
        }
        Write-Warning "ハッシュが異なる既存モデルを置換します: $target"
    }
}

if (-not (Get-Command 'curl.exe' -ErrorAction SilentlyContinue)) {
    throw 'curl.exe が見つかりません。Windowsのcurlをインストールするか手動でモデルを配置してください。'
}
New-Item -ItemType Directory -Path $dir -Force | Out-Null
if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
Write-Host "MuScriptor Small GGUF（約412 MB）を取得します: $source"
Write-Host "配布元の利用条件は元の CC BY-NC 4.0 を継承します。"

try {
    & curl.exe --fail --location --retry 3 --connect-timeout 30 --progress-bar --output $temp $source
    if ($LASTEXITCODE -ne 0) {
        throw "GGUFダウンロードに失敗しました（curl終了コード $LASTEXITCODE）"
    }
    Assert-ModelFile $temp
    Move-Item -LiteralPath $temp -Destination $target -Force
    Write-Host "配置完了: $target" -ForegroundColor Green
    Write-Host "次は Android ARM64 用 libaudiocpp.so を準備し、APKをビルドしてください。"
} finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
}
