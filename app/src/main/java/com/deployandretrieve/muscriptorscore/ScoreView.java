package com.deployandretrieve.muscriptorscore;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lightweight four-measure page preview. MusicXML is the full notation export. */
public final class ScoreView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<NoteEvent> notes=new ArrayList<>();
    private String instrument=null;
    private int bpm=120;
    private int page=0;
    private static final int INK=0xff18342f;
    public ScoreView(Context context){super(context);setBackgroundColor(Color.WHITE);}
    public void setData(List<NoteEvent> notes,String instrument,int bpm,int page){
        this.notes=new ArrayList<>(notes);this.instrument=instrument;this.bpm=bpm;this.page=page;
        int count=instrument==null?Math.min(8,partNames().size()):1;
        getLayoutParamsSafe(count);
        invalidate();
    }
    private void getLayoutParamsSafe(int count){
        if(getLayoutParams()!=null){getLayoutParams().height=Math.max(300,45+Math.max(1,count)*135);requestLayout();}
    }
    private List<String> partNames(){
        Map<String,Boolean> keys=new LinkedHashMap<>();
        for(NoteEvent n:notes)keys.put(n.instrument,true);
        return new ArrayList<>(keys.keySet());
    }
    private void color(int c,int size,Paint.Style style){paint.setColor(c);paint.setStrokeWidth(size);paint.setStyle(style);paint.setTextSize(30);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float w=getWidth(),left=65,right=Math.max(left+100,w-20);
        double barSeconds=240.0/bpm;
        double from=page*4*barSeconds,to=from+4*barSeconds;
        List<String> parts=partNames();
        if(instrument!=null){parts.clear();parts.add(instrument);}
        else if(parts.size()>8){parts=parts.subList(0,8);}
        if(parts.isEmpty()){color(0xff778a85,3,Paint.Style.FILL);c.drawText("解析後に楽譜が表示されます",26,80,paint);return;}
        for(int p=0;p<parts.size();p++){
            String part=parts.get(p);
            float top=55+p*135;
            color(INK,2,Paint.Style.FILL);paint.setTextSize(22);
            c.drawText(Instruments.display(part),20,top-19,paint);
            color(0xff9cb4ab,1,Paint.Style.STROKE);
            for(int line=0;line<5;line++){
                float y=top+line*12;
                c.drawLine(left,y,right,y,paint);
            }
            for(int bar=0;bar<=4;bar++){
                float x=left+(right-left)*bar/4;
                color(0xffb7c8c3,1,Paint.Style.STROKE);
                c.drawLine(x,top,x,top+48,paint);
                if(bar<4){color(0xff83928d,1,Paint.Style.FILL);paint.setTextSize(16);
                    c.drawText(String.valueOf(page*4+bar+1),x+3,top-4,paint);}
            }
            boolean bass=Instruments.isBass(part);
            int bottomLinePitch=bass?43:64; // G2 bass / E4 treble
            color(INK,1,Paint.Style.FILL);paint.setTextSize(24);
            c.drawText(bass?"𝄢":part.equals("drums")?"𝄥":"𝄞",left-37,top+30,paint);
            for(NoteEvent n:notes){
                if(!part.equals(n.instrument)||n.startSeconds<from||n.startSeconds>=to)continue;
                float x=(float)(left+15+(right-left-30)*(n.startSeconds-from)/(to-from));
                // Each diatonic step is a staff space or line (6px).
                int steps=diatonic(n.pitch)-diatonic(bottomLinePitch);
                float y=top+48-steps*6;
                color(INK,1,Paint.Style.FILL);
                c.save();c.rotate(-18,x,y);c.drawOval(x-6,y-4,x+6,y+4,paint);c.restore();
                c.drawRect(x+5,y-28,x+7,y,paint);
                if(y>top+48||y<top){color(INK,1,Paint.Style.STROKE);
                    for(float ledger=top+60;ledger<=y+2&&y>top+48;ledger+=12)c.drawLine(x-10,ledger,x+12,ledger,paint);
                    for(float ledger=top-12;ledger>=y-2&&y<top;ledger-=12)c.drawLine(x-10,ledger,x+12,ledger,paint);
                }
            }
        }
        color(0xff667e77,1,Paint.Style.FILL);paint.setTextSize(18);
        c.drawText("4/4  ♩="+bpm+"   小節 "+(page*4+1)+"〜"+(page*4+4)+"  (簡易プレビュー)",22,getHeight()-15,paint);
    }
    private static int diatonic(int pitch){
        int[] scale={0,0,1,1,2,3,3,4,4,5,5,6};
        return (pitch/12)*7+scale[Math.floorMod(pitch,12)];
    }
}
