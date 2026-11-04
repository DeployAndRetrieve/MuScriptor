package com.deployandretrieve.muscriptorscore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Format-1 Standard MIDI File. Note times use an editable fixed-tempo map. */
public final class MidiExporter {
    private MidiExporter() {}
    private static final int PPQ=480;
    private static class Message {
        long tick; byte[] bytes; int order;
        Message(long tick,byte[] bytes,int order){this.tick=tick;this.bytes=bytes;this.order=order;}
    }
    private static byte[] vlq(long x){
        ByteArrayOutputStream o=new ByteArrayOutputStream();
        long value=Math.max(0,x);
        byte[] arr=new byte[10];int len=0;
        arr[len++]=(byte)(value&127);
        while((value>>=7)>0)arr[len++]=(byte)((value&127)|128);
        for(int i=len-1;i>=0;i--)o.write(arr[i]);
        return o.toByteArray();
    }
    static long ticks(double seconds,int bpm){return Math.max(0,Math.round(seconds*bpm*PPQ/60.0));}
    private static byte[] track(List<Message> events){
        try{
            events.sort(Comparator.comparingLong((Message e)->e.tick).thenComparingInt(e->e.order));
            ByteArrayOutputStream out=new ByteArrayOutputStream();long cursor=0;
            for(Message event:events){
                out.write(vlq(event.tick-cursor));out.write(event.bytes);cursor=event.tick;
            }
            out.write(0);out.write(new byte[]{(byte)0xFF,0x2F,0});
            return out.toByteArray();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static byte[] metaName(String name){
        byte[] txt=name.getBytes(StandardCharsets.UTF_8);
        try {ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(0xFF);out.write(0x03);out.write(vlq(txt.length));out.write(txt);return out.toByteArray();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static byte[] write(List<NoteEvent> notes,int bpm){
        if(bpm<20||bpm>300)throw new IllegalArgumentException("BPM 20〜300");
        try{
            Map<String,List<NoteEvent>> grouped=new LinkedHashMap<>();
            for(NoteEvent n:notes)grouped.computeIfAbsent(n.instrument,k->new ArrayList<>()).add(n);
            List<byte[]> tracks=new ArrayList<>();
            int microPerQuarter=60_000_000/bpm;
            List<Message> conductor=new ArrayList<>();
            conductor.add(new Message(0,new byte[]{(byte)0xFF,0x51,3,(byte)(microPerQuarter>>>16),(byte)(microPerQuarter>>>8),(byte)microPerQuarter},0));
            conductor.add(new Message(0,new byte[]{(byte)0xFF,0x58,4,4,2,24,8},0));
            tracks.add(track(conductor));
            int nextChannel=0;
            for(Map.Entry<String,List<NoteEvent>> part:grouped.entrySet()){
                boolean drum=part.getKey().equals("drums");
                int channel;
                if(drum)channel=9;
                else{
                    if(nextChannel==9)nextChannel++;
                    channel=nextChannel<16?nextChannel:nextChannel%16;
                    if(channel==9)channel=0;
                    nextChannel++;
                }
                List<Message> events=new ArrayList<>();
                events.add(new Message(0,metaName(Instruments.display(part.getKey())),0));
                if(!drum)events.add(new Message(0,new byte[]{(byte)(0xC0|channel),(byte)Instruments.program(part.getKey())},0));
                for(NoteEvent note:part.getValue()){
                    long start=ticks(note.startSeconds,bpm);
                    long end=Math.max(start+1,ticks(note.endSeconds,bpm));
                    events.add(new Message(start,new byte[]{(byte)(0x90|channel),(byte)note.pitch,80},2));
                    events.add(new Message(end,new byte[]{(byte)(0x80|channel),(byte)note.pitch,0},1));
                }
                tracks.add(track(events));
            }
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            DataOutputStream d=new DataOutputStream(output);
            d.writeBytes("MThd");d.writeInt(6);d.writeShort(1);d.writeShort(tracks.size());d.writeShort(PPQ);
            for(byte[] t:tracks){d.writeBytes("MTrk");d.writeInt(t.length);d.write(t);}
            d.flush();return output.toByteArray();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
