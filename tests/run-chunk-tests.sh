#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="$ROOT/app/src/main/java/com/deployandretrieve/muscriptorscore"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
javac -encoding UTF-8 -d "$TMP" "$PKG/NoteEvent.java" "$PKG/Instruments.java" "$PKG/ChunkMerger.java" "$ROOT/tests/ChunkMergerSmokeTest.java"
java -cp "$TMP" ChunkMergerSmokeTest
