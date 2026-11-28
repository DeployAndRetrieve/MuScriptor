package com.deployandretrieve.muscriptorscore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stitch independent, 30-second transcription windows with five seconds of
 * overlap. Each model event has window-relative timestamps.
 *
 * Duplicate recognition is restricted to the overlap and to one-to-one,
 * strongly overlapping notes with the same instrument and MIDI pitch.
 * Notes outside the overlap, including repeated notes of the same pitch,
 * are retained. This is heuristic reconciliation, not model-state stitching.
 */
public final class ChunkMerger {
    public static final int WINDOW_SECONDS = 30;
    public static final int OVERLAP_SECONDS = 5;
    public static final int STRIDE_SECONDS = WINDOW_SECONDS - OVERLAP_SECONDS;
    private static final double EPS = 1.0e-6;
    private ChunkMerger() {}

    public static int chunkCount(int totalSamples, int sampleRate) {
        if (totalSamples <= 0 || sampleRate <= 0) throw new IllegalArgumentException("Empty audio");
        long window = (long) WINDOW_SECONDS * sampleRate;
        long stride = (long) STRIDE_SECONDS * sampleRate;
        if (totalSamples <= window) return 1;
        return 1 + (int) (((long) totalSamples - window + stride - 1) / stride);
    }

    public static int startSample(int chunkIndex, int sampleRate) {
        if (chunkIndex < 0 || sampleRate <= 0) throw new IllegalArgumentException("Bad window index");
        long start = (long) chunkIndex * STRIDE_SECONDS * sampleRate;
        if (start > Integer.MAX_VALUE) throw new IllegalArgumentException("Audio too long");
        return (int) start;
    }

    /**
     * Merge a completed window; returns a new sorted list without modifying
     * either input. No same-window notes are deduplicated.
     */
    public static List<NoteEvent> merge(List<NoteEvent> previous, List<NoteEvent> local,
                                        int chunkIndex, int sampleRate, int windowSamples) {
        if (previous == null || local == null || sampleRate <= 0 || windowSamples <= 0)
            throw new IllegalArgumentException("Bad merge inputs");
        double offset = startSample(chunkIndex, sampleRate) / (double) sampleRate;
        double duration = windowSamples / (double) sampleRate;
        double end = offset + duration;
        double overlapEnd = offset + OVERLAP_SECONDS;
        List<NoteEvent> all = new ArrayList<>();
        for (NoteEvent note : previous) {
            all.add(new NoteEvent(note.instrument, note.pitch, note.startSeconds, note.endSeconds));
        }
        final int oldSize = all.size();
        boolean[] matched = new boolean[oldSize];

        for (NoteEvent note : local) {
            if (note == null || note.instrument == null || note.instrument.isEmpty()
                    || note.pitch < 0 || note.pitch > 127
                    || !Double.isFinite(note.startSeconds) || !Double.isFinite(note.endSeconds))
                continue;
            double a = Math.max(0, note.startSeconds);
            double b = Math.min(duration, note.endSeconds);
            if (a >= duration || b <= a + EPS) continue;
            NoteEvent absolute = new NoteEvent(note.instrument, note.pitch,
                    Math.max(0, offset + a), Math.min(end, offset + b));

            int winner = -1;
            double best = 0;
            if (chunkIndex > 0 && absolute.startSeconds <= overlapEnd + 0.15) {
                for (int i = 0; i < oldSize; i++) {
                    NoteEvent old = all.get(i);
                    if (matched[i] || old.pitch != absolute.pitch
                            || !old.instrument.equals(absolute.instrument)
                            || old.endSeconds < offset - 0.15
                            || old.startSeconds > overlapEnd + 0.15) continue;
                    double intersection = Math.min(old.endSeconds, absolute.endSeconds)
                            - Math.max(old.startSeconds, absolute.startSeconds);
                    double shorter = Math.min(old.endSeconds - old.startSeconds,
                            absolute.endSeconds - absolute.startSeconds);
                    // Avoid joining distinct retriggers of the same MIDI pitch.
                    boolean sameOnset = Math.abs(old.startSeconds - absolute.startSeconds) <= 0.4;
                    boolean clippedAtWindow = Math.abs(absolute.startSeconds - offset) <= 0.12;
                    if (!sameOnset && !clippedAtWindow) continue;
                    if (shorter <= EPS || intersection <= 0
                            || intersection < shorter * 0.55) continue;
                    double score = intersection / shorter
                            - Math.abs(old.startSeconds - absolute.startSeconds) * 0.005;
                    if (winner < 0 || score > best) {
                        best = score;
                        winner = i;
                    }
                }
            }
            if (winner >= 0) {
                NoteEvent old = all.get(winner);
                // A note starting at the new window's left edge can be a
                // clipped continuation. Prefer the original onset observed by
                // the previous window over this artificial boundary.
                if (absolute.startSeconds > offset + 0.12) {
                    old.startSeconds = Math.min(old.startSeconds, absolute.startSeconds);
                }
                old.endSeconds = Math.max(old.endSeconds, absolute.endSeconds);
                matched[winner] = true;
            } else {
                all.add(absolute);
            }
        }
        all.sort(Comparator.comparingDouble((NoteEvent n) -> n.startSeconds)
                .thenComparing(n -> n.instrument).thenComparingInt(n -> n.pitch)
                .thenComparingDouble(n -> n.endSeconds));
        return all;
    }
}
