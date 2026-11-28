import com.deployandretrieve.muscriptorscore.ChunkMerger;
import com.deployandretrieve.muscriptorscore.NoteEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ChunkMergerSmokeTest {
    private static final int RATE = 16000;
    private static NoteEvent n(String ins, int pitch, double a, double b) {
        return new NoteEvent(ins, pitch, a, b);
    }
    private static void check(boolean result, String detail) {
        if (!result) throw new AssertionError(detail);
    }
    private static void near(double actual, double expected, String detail) {
        check(Math.abs(actual - expected) < 0.001,
                detail + ": expected " + expected + " got " + actual);
    }
    private static List<NoteEvent> merge(List<NoteEvent> previous,
            List<NoteEvent> local, int index, int seconds) {
        return ChunkMerger.merge(previous, local, index, RATE, seconds * RATE);
    }
    public static void main(String[] args) {
        check(ChunkMerger.chunkCount(10 * RATE, RATE) == 1, "10 sec count");
        check(ChunkMerger.chunkCount(30 * RATE, RATE) == 1, "30 sec count");
        check(ChunkMerger.chunkCount(31 * RATE, RATE) == 2, "31 sec count");
        check(ChunkMerger.chunkCount(55 * RATE, RATE) == 2, "55 sec count");
        check(ChunkMerger.chunkCount(56 * RATE, RATE) == 3, "56 sec count");
        check(ChunkMerger.chunkCount(600 * RATE, RATE) == 24, "10-minute count");
        check(ChunkMerger.startSample(1, RATE) == 25 * RATE, "second chunk starts at 25");
        check(ChunkMerger.startSample(2, RATE) == 50 * RATE, "third chunk starts at 50");

        List<NoteEvent> first = merge(Collections.emptyList(), Arrays.asList(
                n("acoustic_piano", 60, 24, 29),
                n("acoustic_piano", 62, 27, 28)), 0, 30);
        List<NoteEvent> joined = merge(first, Arrays.asList(
                n("acoustic_piano", 60, 0, 4.1), // clipped at 25, same note
                n("acoustic_piano", 62, 2.08, 3.1), // onset jitter in overlap
                n("electric_bass", 40, 6, 7)), 1, 30);
        check(joined.size() == 3, "duplicate notes must be merged");
        near(joined.get(0).startSeconds, 24.0, "original onset preserved");
        near(joined.get(0).endSeconds, 29.1, "extended end");
        check(joined.get(1).pitch == 62, "second pitch retained");
        near(joined.get(2).startSeconds, 31.0, "offset uses chunk start");

        List<NoteEvent> crossing = merge(
                merge(Collections.emptyList(), Arrays.asList(n("violin", 72, 28, 30)), 0, 30),
                Arrays.asList(n("violin", 72, 0, 7.0)), 1, 30);
        check(crossing.size() == 1, "cross-boundary note should stitch");
        near(crossing.get(0).startSeconds, 28.0, "do not invent early onset at crop edge");
        near(crossing.get(0).endSeconds, 32.0, "cross-boundary note end");

        List<NoteEvent> repeats = merge(
                merge(Collections.emptyList(), Arrays.asList(
                        n("acoustic_piano", 60, 26, 27),
                        n("acoustic_piano", 60, 28, 29)), 0, 30),
                Arrays.asList(n("acoustic_piano", 60, 1.1, 2.1),
                        n("acoustic_piano", 60, 3.1, 4.1)), 1, 30);
        check(repeats.size() == 2, "separate retriggers must not be collapsed");

        List<NoteEvent> untouched = merge(
                merge(Collections.emptyList(), Arrays.asList(n("drums", 36, 8, 8.1)), 0, 30),
                Arrays.asList(n("drums", 36, 12, 12.1)), 1, 30);
        check(untouched.size() == 2, "same pitch outside overlap remains distinct");
        check(untouched.get(1).startSeconds >= 37.0, "new window absolute timestamp");

        // Input must not be modified: checkpoint snapshots must remain immutable.
        near(first.get(0).endSeconds, 29.0, "prior chunk's list not mutated");
        System.out.println("PASS: 30/5 windows, timestamp offsets, seam dedup, " +
                "cross-boundary continuity, repeated notes, input immutability");
    }
}
