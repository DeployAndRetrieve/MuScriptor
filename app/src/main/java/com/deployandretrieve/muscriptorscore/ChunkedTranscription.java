package com.deployandretrieve.muscriptorscore;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-process-owned transcription worker. 30-second independent inference
 * windows advance by 25 seconds; each successful window is merged and durably
 * checkpointed before the next one starts. Reopening the app resumes at the
 * last committed window instead of rerunning the entire song.
 *
 * This is not a ForegroundService: Android may still terminate the process in
 * the background. Checkpoints survive process death, but native in-flight
 * inference of an incomplete window does not.
 */
public final class ChunkedTranscription {
    private static final String CHECKPOINT = "transcription-checkpoint.json";
    private static final String PROJECT = "project.json";
    private static final int FORMAT = 1;
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "muscriptor-chunk-worker");
                t.setDaemon(false);
                return t;
            });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean running;
    private static volatile boolean pauseRequested;
    private static volatile Listener listener;
    private static volatile String status = "";

    public interface Listener {
        void onState(String text);
        void onProgress(int finished, int total, List<NoteEvent> merged);
        void onFinished(List<NoteEvent> merged);
        void onPaused(int finished, int total);
        void onFailed(String message);
    }

    public static final class Partial {
        public final String sourceUri;
        public final int completed;
        public final int total;
        public final List<NoteEvent> notes;
        private Partial(String sourceUri, int completed, int total, List<NoteEvent> notes) {
            this.sourceUri = sourceUri;
            this.completed = completed;
            this.total = total;
            this.notes = new ArrayList<>(notes);
        }
    }

    private static final class Checkpoint {
        String uri;
        String fingerprint;
        String modelId;
        int samples;
        int next;
        List<NoteEvent> notes = new ArrayList<>();

        JSONObject json() throws Exception {
            JSONObject result = new JSONObject();
            result.put("format", FORMAT);
            result.put("uri", uri);
            result.put("fingerprint", fingerprint);
            result.put("model", modelId);
            result.put("samples", samples);
            result.put("sampleRate", AudioDecoder.SAMPLE_RATE);
            result.put("windowSeconds", ChunkMerger.WINDOW_SECONDS);
            result.put("overlapSeconds", ChunkMerger.OVERLAP_SECONDS);
            result.put("nextChunk", next);
            result.put("notes", new JSONArray(TranscriptionParser.serialize(notes)));
            return result;
        }

        static Checkpoint parse(String json) throws Exception {
            JSONObject item = new JSONObject(json);
            if (item.getInt("format") != FORMAT ||
                    item.getInt("sampleRate") != AudioDecoder.SAMPLE_RATE ||
                    item.getInt("windowSeconds") != ChunkMerger.WINDOW_SECONDS ||
                    item.getInt("overlapSeconds") != ChunkMerger.OVERLAP_SECONDS)
                throw new IOException("採譜チェックポイントの形式が現在のアプリと一致しません");
            Checkpoint cp = new Checkpoint();
            cp.uri = item.getString("uri");
            cp.fingerprint = item.getString("fingerprint");
            cp.modelId = item.getString("model");
            cp.samples = item.getInt("samples");
            cp.next = item.getInt("nextChunk");
            cp.notes = TranscriptionParser.deserialize(item.getJSONArray("notes").toString());
            if (cp.uri.isEmpty() || cp.fingerprint.isEmpty() || cp.modelId.isEmpty()
                    || cp.samples < 1 || cp.next < 0 ||
                    cp.next > ChunkMerger.chunkCount(cp.samples, AudioDecoder.SAMPLE_RATE))
                throw new IOException("採譜チェックポイントの進捗情報が不正です");
            return cp;
        }
    }

    private ChunkedTranscription() {}

    public static boolean isRunning() { return running; }
    public static boolean hasCheckpoint(Context context) { return checkpointFile(context).isFile(); }

    public static void attach(Listener receiver) {
        listener = receiver;
        if (receiver != null && running) {
            String text = status;
            MAIN.post(() -> {
                Listener current = listener;
                if (current != null && running && !text.isEmpty()) current.onState(text);
            });
        }
    }

    public static void detach(Listener receiver) {
        if (listener == receiver) listener = null;
    }

    public static synchronized boolean start(Context context, Uri uri, File model, Listener receiver) {
        if (running) {
            attach(receiver);
            return false;
        }
        if (uri == null || model == null || !model.isFile()) {
            throw new IllegalArgumentException("採譜音源またはモデルがありません");
        }
        listener = receiver;
        running = true;
        pauseRequested = false;
        status = "音声デコード中…";
        Context app = context.getApplicationContext();
        String source = uri.toString();
        String modelPath = model.getAbsolutePath();
        EXECUTOR.execute(() -> execute(app, source, modelPath));
        emitState(status);
        return true;
    }

    /** Requests a pause at the next successful checkpoint (not during native inference). */
    public static void requestPause() {
        pauseRequested = true;
        emitState("この区間の終了後に一時停止します…");
    }

    public static Partial readPartial(Context context) throws Exception {
        Checkpoint cp = read(context);
        return cp == null ? null : new Partial(cp.uri, cp.next,
                ChunkMerger.chunkCount(cp.samples, AudioDecoder.SAMPLE_RATE), cp.notes);
    }

    /** Only call when no worker is active and the user explicitly replaces the source. */
    public static synchronized void discardCheckpoint(Context context) {
        if (running) throw new IllegalStateException("採譜中に進捗を削除できません");
        File file = checkpointFile(context);
        if (file.isFile() && !file.delete())
            throw new IllegalStateException("チェックポイントを削除できません");
    }

    private static void execute(Context app, String source, String modelPath) {
        int completed = 0;
        int total = 0;
        boolean paused = false;
        try {
            float[] pcm = AudioDecoder.decode(app, Uri.parse(source));
            if (pcm.length == 0) throw new IOException("音声データが空です");
            total = ChunkMerger.chunkCount(pcm.length, AudioDecoder.SAMPLE_RATE);
            String fingerprint = fingerprint(pcm);
            String modelId = bundledModelId(app);
            Checkpoint cp = read(app);
            if (cp == null) {
                cp = new Checkpoint();
                cp.uri = source;
                cp.fingerprint = fingerprint;
                cp.modelId = modelId;
                cp.samples = pcm.length;
                cp.next = 0;
                saveCheckpoint(app, cp);
            } else if (!cp.uri.equals(source) || !cp.fingerprint.equals(fingerprint) ||
                    !cp.modelId.equals(modelId) || cp.samples != pcm.length) {
                throw new IOException("保存済みの進捗と音源またはモデルが異なります。別の音源を選ぶ場合は進捗を破棄してください");
            }
            completed = cp.next;
            emitProgress(completed, total, cp.notes);
            long analyzer = 0;
            try {
                if (cp.next < total) {
                    analyzer = NativeBridge.nativeOpenAnalyzer(modelPath);
                    if (analyzer == 0) throw new IOException("MuScriptor推論セッションを作成できません");
                }
                for (int index = cp.next; index < total; index++) {
                    if (pauseRequested) { paused = true; break; }
                    int start = ChunkMerger.startSample(index, AudioDecoder.SAMPLE_RATE);
                    int end = Math.min(pcm.length,
                            start + ChunkMerger.WINDOW_SECONDS * AudioDecoder.SAMPLE_RATE);
                    emitState("採譜中: " + (index + 1) + "/" + total +
                            " 区間 (" + (start / AudioDecoder.SAMPLE_RATE) + "秒から、5秒重複)");
                    float[] chunk = Arrays.copyOfRange(pcm, start, end);
                    String events = NativeBridge.nativeAnalyzeChunk(analyzer, chunk,
                                AudioDecoder.SAMPLE_RATE, 2000);
                    List<NoteEvent> local = TranscriptionParser.parse(events);
                    List<NoteEvent> merged = ChunkMerger.merge(cp.notes, local,
                            index, AudioDecoder.SAMPLE_RATE, chunk.length);
                    cp.notes = merged;
                    cp.next = index + 1;
                    // Persist note events and the next window in the same atomic file.
                    saveCheckpoint(app, cp);
                    completed = cp.next;
                    emitProgress(completed, total, merged);
                }
            } finally {
                if (analyzer != 0) NativeBridge.nativeCloseAnalyzer(analyzer);
            }
            if (paused) {
                final int done = completed, count = total;
                MAIN.post(() -> {
                    Listener callback = listener;
                    if (callback != null) callback.onPaused(done, count);
                });
                return;
            }
            if (cp.notes.isEmpty()) throw new IOException("全区間で音符が検出されませんでした");
            writeAtomically(new File(app.getFilesDir(), PROJECT),
                    TranscriptionParser.serialize(cp.notes).getBytes(StandardCharsets.UTF_8));
            // Only delete the checkpoint AFTER successfully saving completed notes.
            File file = checkpointFile(app);
            if (file.exists() && !file.delete())
                throw new IOException("採譜結果は保存しましたが、チェックポイントの削除に失敗しました");
            List<NoteEvent> result = new ArrayList<>(cp.notes);
            MAIN.post(() -> {
                Listener callback = listener;
                if (callback != null) callback.onFinished(result);
            });
        } catch (Throwable e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            MAIN.post(() -> {
                Listener callback = listener;
                if (callback != null) callback.onFailed(message);
            });
        } finally {
            running = false;
            pauseRequested = false;
            status = "";
        }
    }

    private static void emitState(String text) {
        status = text;
        MAIN.post(() -> {
            Listener callback = listener;
            if (callback != null) callback.onState(text);
        });
    }

    private static void emitProgress(int complete, int total, List<NoteEvent> values) {
        List<NoteEvent> snapshot = new ArrayList<>(values);
        MAIN.post(() -> {
            Listener callback = listener;
            if (callback != null) callback.onProgress(complete, total, snapshot);
        });
    }

    private static File checkpointFile(Context context) {
        return new File(context.getFilesDir(), CHECKPOINT);
    }

    private static Checkpoint read(Context context) throws Exception {
        File file = checkpointFile(context);
        if (!file.isFile()) return null;
        byte[] data = Files.readAllBytes(file.toPath());
        return Checkpoint.parse(new String(data, StandardCharsets.UTF_8));
    }

    private static void saveCheckpoint(Context context, Checkpoint cp) throws Exception {
        writeAtomically(checkpointFile(context),
                cp.json().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAtomically(File destination, byte[] bytes) throws IOException {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write(bytes);
                stream.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static String bundledModelId(Context app) throws Exception {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                app.getAssets().open("models/muscriptor-small-f32.gguf.sha256"),
                StandardCharsets.US_ASCII))) {
            String line = input.readLine();
            if (line == null || !line.matches("[0-9a-fA-F]{64} [0-9]+"))
                throw new IOException("モデルの検証情報が不正です");
            return line;
        }
    }

    private static String fingerprint(float[] pcm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : pcm) {
            if (buffer.remaining() < 4) {
                digest.update(buffer.array(), 0, buffer.position());
                buffer.clear();
            }
            buffer.putFloat(sample);
        }
        if (buffer.position() > 0) digest.update(buffer.array(), 0, buffer.position());
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }
}
