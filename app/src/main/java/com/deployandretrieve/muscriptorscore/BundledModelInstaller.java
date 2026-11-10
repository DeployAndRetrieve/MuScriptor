package com.deployandretrieve.muscriptorscore;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Installs a licensed, app-bundled GGUF into a private, path-addressable file.
 * All I/O must run on a background worker; the JNI engine needs a filesystem path.
 */
public final class BundledModelInstaller {
    private static final String MODEL_NAME = "muscriptor-small-f32.gguf";
    private static final String ASSET_DIR = "models/";
    private static final long MIN_BYTES = 40_000_000L;
    private static final long FREE_SPACE_MARGIN = 16L * 1024 * 1024;
    private BundledModelInstaller() {}

    public static File target(Context context) {
        return new File(new File(context.getFilesDir(), "models"), MODEL_NAME);
    }

    /** Called on a worker thread, never the UI thread. */
    public static File ensureInstalled(Context context) throws IOException {
        String manifest = readManifest(context);
        String[] parts = manifest.trim().split("\\s+");
        if (parts.length != 2 || !parts[0].matches("[0-9a-fA-F]{64}")) {
            throw new IOException("APKに内包されたモデル検証情報が不正です");
        }
        final String expectedHash = parts[0].toLowerCase(Locale.US);
        final long expectedBytes;
        try {
            expectedBytes = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("APKのモデルサイズ検証情報が不正です", e);
        }
        if (expectedBytes < MIN_BYTES || expectedBytes > 2_000_000_000L) {
            throw new IOException("内包モデルのサイズが不正です");
        }

        File destination = target(context);
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("モデル格納フォルダーを作成できません");
        }
        // Verify the existing copy, so APK updates and interrupted first installs
        // never cause a mismatched model to be passed to native inference.
        if (destination.isFile() && destination.length() == expectedBytes
                && expectedHash.equals(sha256(destination))) {
            return destination;
        }
        if (parent.getUsableSpace() < expectedBytes + FREE_SPACE_MARGIN) {
            throw new IOException("モデルの初期配置に必要な空き容量がありません（約 "
                    + (expectedBytes / 1_048_576L + 16) + " MiB 以上必要）");
        }

        File temporary = new File(parent, MODEL_NAME + ".tmp");
        try {
            MessageDigest digest = newDigest();
            long copied = 0;
            try (InputStream input = context.getAssets().open(ASSET_DIR + MODEL_NAME);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[1024 * 1024];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    output.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    copied += n;
                    if (copied > expectedBytes) {
                        throw new IOException("内包モデルが検証情報より大きくなっています");
                    }
                }
                output.getFD().sync();
            }
            if (copied != expectedBytes || !expectedHash.equals(hex(digest.digest()))) {
                throw new IOException("内包モデルのSHA-256検証に失敗しました");
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static String readManifest(Context context) throws IOException {
        try (InputStream input = context.getAssets().open(ASSET_DIR + MODEL_NAME + ".sha256")) {
            byte[] bytes = new byte[256];
            int read = 0, n;
            while (read < bytes.length
                    && (n = input.read(bytes, read, bytes.length - read)) != -1) {
                read += n;
            }
            if (read == bytes.length) throw new IOException("モデル検証情報が長すぎます");
            return new String(bytes, 0, read, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IOException("APKにモデルが内包されていません: " + e.getMessage(), e);
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = newDigest();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] data) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = digits[(data[i] >>> 4) & 15];
            out[i * 2 + 1] = digits[data[i] & 15];
        }
        return new String(out);
    }
}
