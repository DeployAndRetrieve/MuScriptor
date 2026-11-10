# モデル内包方式 — MuScriptor Score v1.2.0

更新日: 2026-11-11
対象: `DeployAndRetrieve/MuScriptor`、Android ARM64、minSdk 26。

## 結論

**内包処理はv1.2.0でソース実装したが、モデル重み自体はリポジトリに存在しない。** また `libaudiocpp.so` もソース配布に含まれない。ユーザーのライセンス受諾と開発PCでの取得・変換・Androidネイティブビルドが必要である。

従来はモデルとエンジンなしでもインストール可能なAPKを生成していた。v1.2.0からは**両方が不足している場合にビルドをエラーで停止**する。以後、モデル未同梱のAPKが誤って成功扱いにならないことを優先する。

## 1. ビルド依存関係

```text
公式MuScriptor Smallモデル (CC BY-NC 4.0、要アクセス権)
  config.json + model.safetensors
      ↓ Prepare-Model.ps1（変換スクリプト、未検証）
native-build/weights/muscriptor-small-f32.gguf   [Git管理対象外]
      │
      ├───────────────┐
      ▼               ▼
prepareBundledModel  Prepare-Native.ps1（未検証）
Gradleタスク           ↓
      │          app/src/main/jniLibs/arm64-v8a/libaudiocpp.so
      │                           │
      └─────────────┬─────────────┘
                    ▼
          app/build/outputs/apk/debug/app-debug.apk
                    │
                    ▼
        起動時 BundledModelInstaller
                    │
                    ▼
 getFilesDir()/models/muscriptor-small-f32.gguf
                    │
                    ▼
 NativeBridge + audio.cpp → 自動採譜（未実機検証）
```

## 2. 重要なファイル

- `app/build.gradle`：`prepareBundledModel`。GGUF/ELF検査、SHA-256、APK assets生成。欠落時はビルド停止。
- `BundledModelInstaller.java`：初回起動時にAPK assetsからモデルをコピー。サイズ／SHA-256一致で検証し、既存コピーが正しければ再利用。
- `MainActivity.java`：「モデルを追加」削除、起動時に非同期モデル配置、準備中は採譜ボタン無効化。
- `scripts/Prepare-Model.ps1`：ライセンス受諾後にローカルへ用意した公式モデルをGGUF化。
- `scripts/Prepare-Native.ps1`：外部audio.cppをAndroid ARM64向けにビルドするための試作スクリプト。
- `scripts/Verify-Apk.ps1`：ZIPとしてAPKを検査し、必須3エントリがあることを確認。
- `scripts/Build-Android.ps1`：Gradleビルド後にAPKエントリの検証。

## 3. ビルドの失敗条件

| 状態 | 動作 |
|---|---|
| GGUF未生成 | 明確なビルドエラー |
| GGUFサイズ不足／`GGUF`ヘッダーでない | ビルドエラー |
| Android ARM64 `libaudiocpp.so` がない | ビルドエラー |
| `.so` がAArch64 ELFでない | ビルドエラー |
| GGUFコピーのハッシュ不一致 | ビルドエラー |
| APKにモデル・SHA情報・`.so`のいずれかがない | `Verify-Apk.ps1` が失敗 |
| 起動時に空き容量不足／検証失敗 | エラー表示、採譜不可 |
| 内包モデルと配置済みモデルがSHA一致 | 追加コピーなし |

**制限**：AArch64 ELFの存在と最低サイズの検査は、動的リンクの依存関係や推論ABIの完全互換性を保証しない。これらは実機テストが必要。

## 4. 安全性・ストレージ

- APKのモデルアセットは拡張子`.gguf`を非圧縮で格納する。JNIが直接モデルファイルパスを要求するため、初回起動時にアプリ専用の通常ファイルへコピーする。
- モデル用チェックサムはビルド時にローカルGGUFから計算し、一緒にAPKへ格納する。これは事故検出用であり、第三者によるAPK改ざん防止はAndroid署名検証に依存する。
- 正しい既存モデルは再利用し、変更や破損があれば再コピーする。コピー中に障害が起きた場合は一時ファイルを削除し、不正なモデルを使わない。
- APKのモデルと展開後のモデルは同時にストレージを消費する。モデルのサイズは実際の変換結果に依存するため固定値としない。
- 途中でアプリを終了すると初回配置が中断される可能性がある。再起動時に再試行する。

## 5. 実行手順

1. Hugging Face公式モデルページで利用条件を確認し、ユーザーが同意する。
2. `config.json` と `model.safetensors` を開発PCに保存する。
3. `scripts/Prepare-Native.ps1`（Android推論ライブラリ、未検証）を実行。
4. `scripts/Prepare-Model.ps1 -CheckpointDir "C:\path\to\checkpoint"`（GGUF、未検証）を実行。
5. `.\gradlew.bat :app:assembleDebug` を実行。
6. `scripts/Verify-Apk.ps1` を実行し、必須エントリのサイズを確認。
7. Android端末へAPKをインストールし、初回起動のモデル配置を確認。
8. 原盤を選び、採譜・書出し・原盤/MIDI同時再生を確認する。

## 6. 実機受入条件

- 初回起動時に「モデルを追加」ボタンが存在しない。
- 内包モデルが自動配置され、SHA検証に成功。再起動時には不必要な再配置がない。
- 端末からネットワークを切断した状態で採譜が完了する。
- 原盤選択→採譜→MIDI/MusicXML出力→比較再生が正常に完了する。
- モデル欠落・壊れたモデル・容量不足は、クラッシュではなく明確なエラーで終了する。

**未検証**：このチャットでは変換・NDKクロスビルド・APKパッケージング・実機採譜を実施していない。モデルを実際に内包したAPKの作成実績はまだない。

## 7. ライセンス

MuScriptorの学習済み重みはCC BY-NC 4.0。認証と規約受諾が必要な場合がある。個人利用を想定していても、モデルや変換物を公開リポジトリ・公開APKとして無条件に再配布してよいわけではない。配布前に最新の条件を確認すること。認証情報、モデル本体、ネイティブのビルド生成物はGitの管理対象外とする。
