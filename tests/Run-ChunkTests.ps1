# Java-only chunk merger checks; works in Windows PowerShell 5.1 (ASCII source).
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pkg = Join-Path $root 'app\src\main\java\com\deployandretrieve\muscriptorscore'
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("muscriptor-chunk-tests-" + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    $sources = @(
        (Join-Path $pkg 'NoteEvent.java'),
        (Join-Path $pkg 'Instruments.java'),
        (Join-Path $pkg 'ChunkMerger.java'),
        (Join-Path $root 'tests\ChunkMergerSmokeTest.java')
    )
    & javac -encoding UTF-8 -d $tmp @sources
    if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
    & java -cp $tmp ChunkMergerSmokeTest
    if ($LASTEXITCODE -ne 0) { throw "ChunkMerger tests failed: $LASTEXITCODE" }
} finally {
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force }
}
