package com.deployandretrieve.muscriptorscore;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Local A/B playback of the selected original audio and a freshly rendered MIDI file.
 * Uses two independent Android MediaPlayers; sync is best-effort, not sample-accurate.
 * All methods and callbacks must run on the Android main thread.
 */
public final class ComparisonPlayback {
    public interface Listener {
        void onChanged();
        void onError(String message);
    }

    private final Context context;
    private final Listener listener;
    private MediaPlayer original;
    private MediaPlayer midi;
    private File midiFile;
    private int generation;
    private boolean originalReady;
    private boolean midiReady;
    private boolean playRequested;
    private boolean playing;
    private float originalGain = 1f;
    private float midiGain = 1f;

    public ComparisonPlayback(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean hasSource() { return original != null && midi != null; }
    public boolean isReady() { return originalReady && midiReady; }
    public boolean isPlaying() { return playing; }
    public boolean isPreparing() { return hasSource() && !isReady(); }

    public int getDurationMs() {
        if (!originalReady || original == null) return 0;
        try { return Math.max(0, original.getDuration()); }
        catch (IllegalStateException e) { return 0; }
    }

    public int getPositionMs() {
        if (!originalReady || original == null) return 0;
        try { return Math.max(0, original.getCurrentPosition()); }
        catch (IllegalStateException e) { return 0; }
    }

    public void setVolumes(float originalVolume, float midiVolume) {
        originalGain = clamp(originalVolume);
        midiGain = clamp(midiVolume);
        if (original != null) original.setVolume(originalGain, originalGain);
        if (midi != null) midi.setVolume(midiGain, midiGain);
    }

    private static float clamp(float f) {
        return Math.max(0f, Math.min(1f, f));
    }

    /**
     * Prepares both sources asynchronously and starts when both are ready.
     * MIDI follows the same notes/part filter as the application's MIDI export.
     */
    public void loadAndPlay(Uri audioUri, List<NoteEvent> notes, int bpm) throws IOException {
        reset();
        if (audioUri == null || notes == null || notes.isEmpty()) {
            throw new IllegalArgumentException("比較する原盤と採譜データを指定してください");
        }
        final int token = generation;
        try {
            byte[] midiBytes = MidiExporter.write(notes, bpm);
            midiFile = File.createTempFile("muscriptor-preview-", ".mid", context.getCacheDir());
            try (FileOutputStream out = new FileOutputStream(midiFile)) {
                out.write(midiBytes);
            }

            original = new MediaPlayer();
            midi = new MediaPlayer();
            final AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            original.setAudioAttributes(attributes);
            midi.setAudioAttributes(attributes);
            setVolumes(originalGain, midiGain);

            original.setOnPreparedListener(player -> {
                if (token != generation) return;
                originalReady = true;
                readyChanged();
            });
            midi.setOnPreparedListener(player -> {
                if (token != generation) return;
                midiReady = true;
                readyChanged();
            });
            original.setOnCompletionListener(player -> {
                if (token != generation) return;
                // The original is the reference timeline; stop the MIDI if it outlasts it.
                playRequested = false;
                playing = false;
                if (midi != null) {
                    try { if (midi.isPlaying()) midi.pause(); }
                    catch (IllegalStateException ignored) {}
                }
                listener.onChanged();
            });
            midi.setOnCompletionListener(player -> {
                if (token == generation) listener.onChanged();
            });
            MediaPlayer.OnErrorListener errors = (player, what, extra) -> {
                if (token == generation) fail("再生エラー（" + what + " / " + extra + "）。端末のMIDI再生対応を確認してください");
                return true;
            };
            original.setOnErrorListener(errors);
            midi.setOnErrorListener(errors);

            original.setDataSource(context, audioUri);
            try (FileInputStream in = new FileInputStream(midiFile)) {
                midi.setDataSource(in.getFD());
            }
            playRequested = true;
            original.prepareAsync();
            midi.prepareAsync();
            listener.onChanged();
        } catch (Exception e) {
            reset();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("再生準備に失敗: " + e.getMessage(), e);
        }
    }

    private void readyChanged() {
        if (isReady() && playRequested) startBoth();
        listener.onChanged();
    }

    private void startBoth() {
        if (!isReady() || !hasSource()) return;
        try {
            int position = getPositionMs();
            int originalDuration = getDurationMs();
            if (originalDuration > 0 && position >= originalDuration - 50) {
                original.seekTo(0);
                midi.seekTo(0);
                position = 0;
            }
            // Start MIDI before the master (original). Minor start latency is
            // periodically corrected; the two MediaPlayers have separate clocks.
            if (position < midi.getDuration() - 30) midi.start();
            original.start();
            playing = true;
            listener.onChanged();
        } catch (IllegalStateException e) {
            fail("同時再生を開始できません: " + e.getMessage());
        }
    }

    public void play() {
        if (!hasSource()) return;
        playRequested = true;
        if (isReady()) startBoth();
        else listener.onChanged();
    }

    public void pause() {
        playRequested = false;
        playing = false;
        try {
            if (original != null && originalReady && original.isPlaying()) original.pause();
            if (midi != null && midiReady && midi.isPlaying()) midi.pause();
        } catch (IllegalStateException e) {
            fail("一時停止に失敗: " + e.getMessage());
            return;
        }
        listener.onChanged();
    }

    public void seekTo(int positionMs) {
        if (!isReady()) return;
        int position = Math.max(0, Math.min(positionMs, getDurationMs()));
        try {
            original.seekTo(position);
            midi.seekTo(Math.min(position, Math.max(0, midi.getDuration())));
            if (playing && position < midi.getDuration() - 50 && !midi.isPlaying()) midi.start();
        } catch (IllegalStateException e) {
            fail("再生位置の変更に失敗: " + e.getMessage());
            return;
        }
        listener.onChanged();
    }

    /** Correct MIDI drift conservatively; never seek the reference track. */
    public void correctDrift() {
        if (!playing || !isReady()) return;
        try {
            if (!midi.isPlaying()) return;
            int reference = original.getCurrentPosition();
            if (reference > midi.getDuration() - 100) return;
            if (Math.abs(reference - midi.getCurrentPosition()) > 250) {
                midi.seekTo(reference);
            }
        } catch (IllegalStateException ignored) {}
    }

    private void fail(String error) {
        reset();
        listener.onError(error);
    }

    public void reset() {
        generation++;
        originalReady = false;
        midiReady = false;
        playRequested = false;
        playing = false;
        if (original != null) {
            original.setOnPreparedListener(null);
            original.setOnErrorListener(null);
            original.setOnCompletionListener(null);
            original.release();
            original = null;
        }
        if (midi != null) {
            midi.setOnPreparedListener(null);
            midi.setOnErrorListener(null);
            midi.setOnCompletionListener(null);
            midi.release();
            midi = null;
        }
        if (midiFile != null) {
            if (midiFile.exists()) midiFile.delete();
            midiFile = null;
        }
        listener.onChanged();
    }
}
