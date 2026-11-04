package com.deployandretrieve.muscriptorscore;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Converts upstream MuScriptor start/end events, joined by start_event_index. */
public final class TranscriptionParser {
    private TranscriptionParser(){}
    public static List<NoteEvent> parse(String json) throws Exception {
        JSONArray arr=new JSONArray(json);
        Map<Integer,NoteEvent> starts=new HashMap<>();
        ArrayList<NoteEvent> notes=new ArrayList<>();
        for(int i=0;i<arr.length();i++){
            JSONObject item=arr.getJSONObject(i);
            if("start".equals(item.optString("type"))) {
                String ins=item.getString("instrument");
                int pitch=item.getInt("pitch");
                double time=item.getDouble("start_time");
                if(pitch<0||pitch>127||time<0||!Double.isFinite(time))continue;
                starts.put(item.getInt("index"),new NoteEvent(ins,pitch,time,time));
            } else if("end".equals(item.optString("type"))) {
                NoteEvent note=starts.remove(item.getInt("start_event_index"));
                if(note==null)continue;
                double stop=item.getDouble("end_time");
                if(!Double.isFinite(stop)||stop<=note.startSeconds)stop=note.startSeconds+0.06;
                note.endSeconds=stop;
                notes.add(note);
            }
        }
        notes.sort(Comparator.comparingDouble((NoteEvent n)->n.startSeconds).thenComparingInt(n->n.pitch));
        return notes;
    }
    public static String serialize(List<NoteEvent> notes) throws Exception {
        JSONArray a=new JSONArray();
        for(NoteEvent n:notes){
            JSONObject o=new JSONObject();
            o.put("instrument",n.instrument);o.put("pitch",n.pitch);
            o.put("start",n.startSeconds);o.put("end",n.endSeconds);a.put(o);
        }
        return a.toString();
    }
    public static List<NoteEvent> deserialize(String data) throws Exception {
        JSONArray a=new JSONArray(data);
        ArrayList<NoteEvent> result=new ArrayList<>();
        for(int i=0;i<a.length();i++){
            JSONObject o=a.getJSONObject(i);
            result.add(new NoteEvent(o.getString("instrument"),o.getInt("pitch"),o.getDouble("start"),o.getDouble("end")));
        }
        return result;
    }
}
