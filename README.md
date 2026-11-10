# MuScriptor Score v1.2.0

Android / ARM64 / 個人・非商用 / 完全オフライン音楽採譜（開発中）

[設計書](docs/DESIGN.md) · [モデル内包手順](docs/BUNDLED_MODEL.md) · [第三者ライセンス表示](THIRD_PARTY_NOTICES.md)

## 現在の状態（重要）

**v1.2.0では、ローカルで取得・変換したGGUFをAPKに組み込み、端末で初回起動時に自動配置する処理を実装しました。** 画面から「モデルを追加」ボタンを削除しています。

**ただし、GitHubリポジトリにはモデル重みもネイティブ推論ライブラリも含まれていません。** このチャットでは利用者のHugging Faceライセンス同意を代行できず、Android向けlibaudiocpp.soのクロスビルドも実機検証できていません。**今あるソースをそのままcloneしただけでは、モデル内包APKを作成できません。**

これまでと異なり、足りないモデルやエンジンがある状態で「ビルド成功」にならないよう、Gradleを修正しました。不足時は明確なエラーでビルドを停止します。「ビルド成功だがモデル未導入」にならないことを優先しています。

## 構成

- JDK 21、Gradle Wrapper 8.13、Android Gradle Plugin 8.13.0
- compileSdk / targetSdk 36、minSdk 26、arm64-v8a
- Java Android + C++ JNI、音声デコードはMediaExtractor/MediaCodec
- MuScriptor Small推論: audio.cpp C API (GGUF/CPU)。Android版C APIのビルド／実動作は未確認
- 音符編集、楽器別MIDI (SMF Format 1)、MusicXML、JSON
- 原盤と生成MIDIの同時比較再生、共通再生/停止/シーク、個別音量/ミュート（実機未検証）
- AndroidManifestにINTERNET権限なし

## モデル内包APKのビルド手順（Windows）

### 1. 公式モデルを取得

[MuScriptor Small](https://huggingface.co/MuScriptor/muscriptor-small) の利用条件（重みはCC BY-NC 4.0）を確認して、**本人が同意**し、次のチェックポイントをローカルへ取得してください。

- `config.json`
- `model.safetensors`

取得にはHugging Faceへのログイン・認証が必要になる場合があります。認証トークンやモデル重みをGitHubへ登録しないでください。モデルを公開配布する場合は別途ライセンス適合性を確認してください。

### 2. NDKで推論ライブラリを準備

Android SDK/NDK、CMake、Ninja、Git、C++開発環境を用意します。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Prepare-Native.ps1
```

成功すると `app/src/main/jniLibs/arm64-v8a/libaudiocpp.so` を生成する設計です。サードパーティーのaudio.cppをAndroidに移植・クロスビルドするこのスクリプトは**未検証**です。ビルドが通らない場合はNDKやaudio.cppのAndroid向け修正が必要です。

### 3. GGUFへ変換

```powershell
python -m pip install numpy safetensors
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Prepare-Model.ps1 -CheckpointDir "C:\path\to\checkpoint"
```

成功時の出力:

```text
native-build/weights/muscriptor-small-f32.gguf
```

このモデル変換スクリプトも現行のaudio.cppとの組み合わせについて**未検証**です。

### 4. ビルド

```powershell
.\gradlew.bat :app:assembleDebug
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Verify-Apk.ps1
```

`scripts/Build-Android.ps1` は Gradle 実行後に APK 内容を自動検証します。通常の `gradlew.bat` や Android Studio ビルドでも、`prepareBundledModel` タスクがモデルと推論ライブラリの存在を検査します。

Gradleは次を実行します：

1. 変換済みGGUFの存在、サイズ、`GGUF`ヘッダーを検査。
2. `libaudiocpp.so` の存在とARM64 ELFヘッダーを検査。
3. GGUFを `app/build/generated/bundledModelAssets/models/` へストリームコピー。
4. 検証用SHA-256とファイル長を `*.sha256` に記録。
5. `assets/models/muscriptor-small-f32.gguf` と検証情報、および `lib/arm64-v8a/libaudiocpp.so` をAPKへ格納。
6. インストール後、初回起動時に専用領域へ自動コピー・ハッシュ検証。2回目以降は正しいモデルを再利用。

`-PmuscriptorModelFile=C:\path\to\model.gguf` を付ければ変換済みモデルの入力元を変更できます（GitHubのパスへコピーする必要はありません）。

**注意:** 検証は「APKにファイルがある」までを確認します。ネイティブ推論の ABI・依存 .so・モデルとの互換性、性能、実際の採譜品質は端末で別途検証してください。巨大なモデルはAPKサイズ・初回コピー用空き容量・RAMを大きく消費します。

## アプリ操作

1. 「MP3を選択」で端末の原盤を指定する（選択先ファイルはアップロードしない）。
2. アプリが起動時に内包モデルを検証・初期配置する。配置中は採譜開始不可。
3. 「この曲を採譜」で楽器別の音符を生成する。
4. 音符やBPMを編集し、楽譜を確認する。
5. 「原盤 ↔ MIDI 聴き比べ」で、原盤と生成MIDIを同時再生する。片側ミュートや音量0で聞き比べできる。
6. 「MIDI保存」「楽譜保存」「JSON保存」でSAFを使って出力する。

### 制限

- 現在の音声デコード上限は10分。モデルロード・JNI呼び出しはメモリを大量に消費する。
- MIDIの楽器音色は端末内MIDI再生機能に依存。比較再生の2系統同期はベストエフォートでありサンプル精度ではない。
- MusicXMLは固定4/4・設定BPM・16分音符グリッドを使用。自動調号／テンポ検出、正確なドラム譜、PDF出力はない。
- 現行プロジェクト保存は1曲分。楽器別ファイルの一括保存や楽譜ファイルの取り込みは未実装。

## テスト

```bash
bash tests/run-export-tests.sh
```

MIDIとMusicXML生成器についての限定的なJVM単体テストです。実機でのAPKビルド・モデル内包・モデル推論・比較再生はこのチャット環境では未確認です。

## ライセンス

- MuScriptorコード：MIT。モデル重み：CC BY-NC 4.0。利用条件と権利を公式配布元で確認。
- audio.cpp：Apache 2.0（追加依存の通知要確認）。
- 個人・非商用の利用を前提とする。モデルと秘密情報をリポジトリにコミットしない。
