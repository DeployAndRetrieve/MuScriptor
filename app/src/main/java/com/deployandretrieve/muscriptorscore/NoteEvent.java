package com.deployandretrieve.muscriptorscore;

import java.util.Locale;

public final class NoteEvent {
    public String instrument;
    public int pitch;
    public double startSeconds;
    public double endSeconds;
    public NoteEvent(String instrument,int pitch,double start,double end) {
        this.instrument=instrument; this.pitch=pitch;
        this.startSeconds=start; this.endSeconds=end;
    }
    public String describe() {
        return String.format(Locale.JAPAN,"%s  %s  %.2f〜%.2f 秒",
                Instruments.display(instrument),Instruments.pitchName(pitch),startSeconds,endSeconds);
    }
}
