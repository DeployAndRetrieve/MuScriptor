#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="$PROJECT/app/src/main/java/com/deployandretrieve/muscriptorscore"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
javac -d "$TMP" "$PKG/NoteEvent.java" "$PKG/Instruments.java" "$PKG/MidiExporter.java" "$PKG/MusicXmlExporter.java" "$PROJECT/tests/ExporterSmokeTest.java"
java -cp "$TMP" ExporterSmokeTest
