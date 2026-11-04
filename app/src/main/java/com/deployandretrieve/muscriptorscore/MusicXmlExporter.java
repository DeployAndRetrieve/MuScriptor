package com.deployandretrieve.muscriptorscore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Basic readable MusicXML Partwise score with overlapping notes assigned to separate voices.
 * Does not infer original beat grid; fixed BPM, 4/4 and 16th-note quantization are user adjustable. */
public final class MusicXmlExporter {
    private MusicXmlExporter(){}
    private static final int PPQ=480, BAR=PPQ*4, GRID=120;
    private static final int[] VALUES={1920,1440,960,720,480,360,240,120};
    private static final String[] TYPES={"whole","half","half","quarter","quarter","eighth","eighth","16th"};
    private static final boolean[] DOT={false,true,false,true,false,true,false,false};
    private static class Timed {
        final NoteEvent note;final int start,end;
        Timed(NoteEvent n,int s,int e){note=n;start=s;end=e;}
    }
    private static class Voice {
        final List<Timed> notes=new ArrayList<>();int end;
    }
    private static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static int quantize(double t,int bpm){
        if(!Double.isFinite(t)||t<0)throw new IllegalArgumentException("Invalid note time");
        return (int)Math.max(0,Math.round(t*bpm*PPQ/(60.0*GRID))*GRID);
    }
    private static List<Voice> voices(List<NoteEvent> notes,int bpm){
        List<Timed> ts=new ArrayList<>();
        for(NoteEvent n:notes){int a=quantize(n.startSeconds,bpm);int b=Math.max(a+GRID,quantize(n.endSeconds,bpm));ts.add(new Timed(n,a,b));}
        ts.sort(Comparator.comparingInt((Timed t)->t.start).thenComparingInt(t->t.note.pitch));
        List<Voice> out=new ArrayList<>();
        for(Timed t:ts){
            Voice selected=null;
            for(Voice v:out)if(v.end<=t.start){selected=v;break;}
            if(selected==null){selected=new Voice();out.add(selected);}
            selected.notes.add(t);selected.end=t.end;
        }
        return out;
    }
    private static void event(StringBuilder s, int dur,int pitch,boolean rest,int voice,boolean before,boolean after,boolean drum){
        while(dur>0){
            int k=VALUES.length-1;
            for(int i=0;i<VALUES.length;i++)if(VALUES[i]<=dur){k=i;break;}
            int piece=VALUES[k];
            boolean pieceContinues=dur>piece;
            s.append("      <note>");
            if(rest)s.append("<rest/>");
            else if(drum){s.append("<unpitched><display-step>C</display-step><display-octave>5</display-octave></unpitched>");}
            else {
                String[] keys={"C","C","D","D","E","F","F","G","G","A","A","B"};
                boolean[] sharp={false,true,false,true,false,false,true,false,true,false,true,false};
                int p=Math.floorMod(pitch,12);
                s.append("<pitch><step>").append(keys[p]).append("</step>");
                if(sharp[p])s.append("<alter>1</alter>");
                s.append("<octave>").append(Math.floorDiv(pitch,12)-1).append("</octave></pitch>");
            }
            s.append("<duration>").append(piece).append("</duration>");
            if(!rest){
                if(before)s.append("<tie type=\"stop\"/>");
                if(after||pieceContinues)s.append("<tie type=\"start\"/>");
            }
            s.append("<voice>").append(voice).append("</voice>")
             .append("<type>").append(TYPES[k]).append("</type>");
            if(DOT[k])s.append("<dot/>");
            if(!rest){
                if(before||after||pieceContinues){
                    s.append("<notations>");
                    if(before)s.append("<tied type=\"stop\"/>");
                    if(after||pieceContinues)s.append("<tied type=\"start\"/>");
                    s.append("</notations>");
                }
            }
            s.append("</note>\n");
            dur-=piece;before=true;
        }
    }
    public static String write(List<NoteEvent> notes,int bpm,String title){
        if(bpm<20||bpm>300)throw new IllegalArgumentException("BPM 20〜300");
        Map<String,List<NoteEvent>> parts=new LinkedHashMap<>();
        for(NoteEvent n:notes)parts.computeIfAbsent(n.instrument,k->new ArrayList<>()).add(n);
        int maxTick=0;
        for(NoteEvent n:notes)maxTick=Math.max(maxTick,quantize(n.endSeconds,bpm));
        int bars=Math.max(1,(maxTick+BAR-1)/BAR);
        StringBuilder s=new StringBuilder();
        s.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
         .append("<score-partwise version=\"4.0\"><work><work-title>").append(esc(title))
         .append("</work-title></work><movement-title>").append(esc(title)).append("</movement-title><identification><creator type=\"software\">MuScriptor Score</creator></identification>\n")
         .append("<part-list>\n");
        int idx=1;
        for(String name:parts.keySet()){
            s.append("<score-part id=\"P").append(idx++).append("\"><part-name>")
             .append(esc(Instruments.display(name))).append("</part-name></score-part>\n");
        }
        s.append("</part-list>\n");idx=1;
        for(Map.Entry<String,List<NoteEvent>> part:parts.entrySet()){
            boolean drum=part.getKey().equals("drums");
            boolean bass=Instruments.isBass(part.getKey());
            List<Voice> vs=voices(part.getValue(),bpm);
            s.append("<part id=\"P").append(idx++).append("\">\n");
            for(int bar=0;bar<bars;bar++){
                int from=bar*BAR, to=from+BAR;
                s.append("<measure number=\"").append(bar+1).append("\">\n");
                if(bar==0){
                    s.append("<attributes><divisions>480</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time><clef>");
                    if(drum)s.append("<sign>percussion</sign>");
                    else if(bass)s.append("<sign>F</sign><line>4</line>");
                    else s.append("<sign>G</sign><line>2</line>");
                    s.append("</clef></attributes>\n<direction placement=\"above\"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>")
                     .append(bpm).append("</per-minute></metronome></direction-type><sound tempo=\"")
                     .append(bpm).append("\"/></direction>\n");
                }
                for(int voice=0;voice<vs.size();voice++){
                    if(voice>0)s.append("<backup><duration>").append(BAR).append("</duration></backup>\n");
                    int cursor=from;
                    for(Timed n:vs.get(voice).notes){
                        if(n.end<=from||n.start>=to)continue;
                        int a=Math.max(from,n.start),b=Math.min(to,n.end);
                        if(a>cursor)event(s,a-cursor,0,true,voice+1,false,false,drum);
                        event(s,b-a,n.note.pitch,false,voice+1,n.start<a,n.end>b,drum);
                        cursor=b;
                    }
                    if(cursor<to)event(s,to-cursor,0,true,voice+1,false,false,drum);
                }
                s.append("</measure>\n");
            }
            s.append("</part>\n");
        }
        s.append("</score-partwise>");return s.toString();
    }
}
