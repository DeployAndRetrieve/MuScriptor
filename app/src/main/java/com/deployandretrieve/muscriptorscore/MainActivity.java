package com.deployandretrieve.muscriptorscore;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_MP3=10,PICK_GGUF=11,EXPORT=12;
    private static final int TEAL=0xff176b62, DARK=0xff123e38;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService jobs=Executors.newSingleThreadExecutor();
    private Uri audioUri;
    private String audioName="未選択";
    private File modelFile;
    private final List<NoteEvent> notes=new ArrayList<>();
    private TextView audioText,modelText,statusText,summary;
    private ProgressBar progress;
    private Button analyzeButton,prevButton,nextButton;
    private Spinner partSpinner;
    private ScoreView score;
    private ListView noteList;
    private int bpm=120,page=0;
    private String filter=null,exportKind;
    private boolean working=false;
    private ComparisonPlayback comparison;
    private Button comparePlayButton, compareStopButton, originalMuteButton, midiMuteButton;
    private SeekBar compareSeekBar, originalVolumeSeek, midiVolumeSeek;
    private TextView compareTime, compareStatus, originalVolumeLabel, midiVolumeLabel;
    private boolean seekingComparison=false;
    private boolean originalMuted=false, midiMuted=false;
    private int originalVolume=100, midiVolume=100;
    private final Runnable compareTicker=new Runnable(){
        @Override public void run(){
            if(comparison!=null){
                comparison.correctDrift();
                updateComparisonUi();
            }
            ui.postDelayed(this,250);
        }
    };

    @Override public void onCreate(Bundle savedInstance){
        super.onCreate(savedInstance);
        setTitle("MuScriptor Score v" + BuildConfig.VERSION_NAME);
        modelFile=new File(getFilesDir(),"models/muscriptor-small-f32.gguf");
        comparison=new ComparisonPlayback(this,new ComparisonPlayback.Listener(){
            @Override public void onChanged(){updateComparisonUi();}
            @Override public void onError(String description){
                updateComparisonUi();
                message(description);
            }
        });
        originalVolume=getPreferences(0).getInt("original_volume",100);
        midiVolume=getPreferences(0).getInt("midi_volume",100);
        loadProject();
        buildUi();
        refresh();
        applyComparisonVolumes();
        ui.postDelayed(compareTicker,250);
    }
    private int dp(float amount){return (int)(getResources().getDisplayMetrics().density*amount+.5f);}
    private TextView text(String value,int size){
        TextView t=new TextView(this);t.setText(value);t.setTextColor(DARK);t.setTextSize(size);t.setPadding(dp(4),dp(7),dp(4),dp(7));return t;
    }
    private Button button(String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setTextColor(TEAL);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;
    }
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(LinearLayout into){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);into.addView(l);return l;}
    private void put(LinearLayout l,View v){l.addView(v,new LinearLayout.LayoutParams(0,dp(54),1));}
    private void buildUi(){
        ScrollView scroller=new ScrollView(this);setContentView(scroller);
        LinearLayout root=vertical();root.setPadding(dp(14),dp(10),dp(14),dp(18));scroller.addView(root);
        TextView header=text("MuScriptor Score v" + BuildConfig.VERSION_NAME,26);header.setTextColor(TEAL);root.addView(header);
        TextView subtitle=text("完全オフライン · 個人・非商用",12);root.addView(subtitle);
        LinearLayout load=row(root);
        put(load,button("MP3を選択",()->pick(PICK_MP3,"audio/*")));
        put(load,button("モデルを追加",()->pick(PICK_GGUF,"*/*")));
        audioText=text("",13);root.addView(audioText);
        modelText=text("",13);root.addView(modelText);
        analyzeButton=button("この曲を採譜",this::analyze);root.addView(analyzeButton,new LinearLayout.LayoutParams(-1,dp(55)));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);progress.setVisibility(View.GONE);root.addView(progress);
        statusText=text("",13);root.addView(statusText);
        summary=text("",14);root.addView(summary);
        LinearLayout action=row(root);
        put(action,button("BPM設定",this::editBpm));
        put(action,button("設定",this::settings));
        partSpinner=new Spinner(this);root.addView(partSpinner);
        partSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                List<String> keys=availableParts();
                if(position>keys.size())return;
                String selected=position==0?null:keys.get(position-1);
                if(!java.util.Objects.equals(filter,selected)){
                    filter=selected;
                    comparison.reset();
                }
                redrawScore();redrawList();
            }
        });
        buildComparisonUi(root);
        LinearLayout pages=row(root);
        prevButton=button("◀ 前の4小節",()->{page=Math.max(0,page-1);redrawScore();});
        nextButton=button("次の4小節 ▶",()->{page++;redrawScore();});
        put(pages,prevButton);put(pages,nextButton);
        score=new ScoreView(this);root.addView(score,new LinearLayout.LayoutParams(-1,dp(320)));
        LinearLayout exports=row(root);
        put(exports,button("MIDI保存",()->saveAs("midi")));
        put(exports,button("楽譜保存",()->saveAs("musicxml")));
        put(exports,button("JSON保存",()->saveAs("json")));
        root.addView(text("検出された音符（タップで修正）",15));
        noteList=new ListView(this);
        noteList.setNestedScrollingEnabled(false);
        root.addView(noteList,new LinearLayout.LayoutParams(-1,dp(220)));
        noteList.setOnItemClickListener((parent,v,pos,id)->{
            List<NoteEvent> visible=visibleNotes();
            if(pos<visible.size())editNote(visible.get(pos));
        });
        root.addView(text("© Kyutai / Mirelo  MuScriptor weights: CC BY-NC 4.0\nNative runtime: audio.cpp (Apache 2.0)",11));
    }
    private void pick(int id,String mime){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType(mime);i.addCategory(Intent.CATEGORY_OPENABLE);
        if(id==PICK_MP3)i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/mpeg","audio/mp3","audio/wav","audio/flac","audio/*"});
        startActivityForResult(i,id);
    }
    @Override protected void onActivityResult(int request,int result,Intent intent){
        super.onActivityResult(request,result,intent);
        if(result!=RESULT_OK||intent==null||intent.getData()==null)return;
        Uri uri=intent.getData();
        if(request==PICK_MP3){
            comparison.reset();
            audioUri=uri;audioName=fileName(uri);
            notes.clear();page=0;filter=null;saveProject();
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            getPreferences(0).edit().putString("audio",uri.toString()).putString("name",audioName).apply();
            refresh();
        } else if(request==PICK_GGUF){importModel(uri);}
        else if(request==EXPORT){exportFile(uri);}
    }
    private String fileName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return "audio";
    }
    private void importModel(Uri uri){
        if(working)return;
        String name=fileName(uri);
        if(!name.toLowerCase(Locale.ROOT).endsWith(".gguf")){
            message(".gguf 形式のMuScriptor Smallモデルを指定してください");return;
        }
        working=true;updateBusy("モデルを内部ストレージにコピーしています…");
        jobs.execute(()->{
            File temp=new File(getCacheDir(),"muscriptor-model.tmp");
            try{
                modelFile.getParentFile().mkdirs();
                try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){
                    if(in==null)throw new IllegalStateException("ファイルを開けません");
                    byte[] buf=new byte[1024*256];int n;long size=0;
                    while((n=in.read(buf))!=-1){out.write(buf,0,n);size+=n;
                        if(size>2_000_000_000L)throw new IllegalArgumentException("モデルが大きすぎます");
                    }
                    if(size<40_000_000)throw new IllegalArgumentException("MuScriptor GGUFとして小さすぎるファイルです");
                }
                Files.move(temp.toPath(),modelFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                ui.post(()->message("モデルを端末に保存しました"));
            }catch(Exception e){temp.delete();ui.post(()->message("モデルの読み込み失敗: "+e.getMessage()));}
            finally{ui.post(()->{working=false;refresh();});}
        });
    }
    private void analyze(){
        if(working)return;
        if(audioUri==null){message("MP3を選択してください");return;}
        if(!modelFile.isFile()){message("まずMuScriptor SmallのGGUFモデルを追加してください");return;}
        working=true;updateBusy("音声を端末内でデコード中…");
        jobs.execute(()->{
            try{
                float[] audio=AudioDecoder.decode(this,audioUri);
                ui.post(()->updateBusy("MuScriptorで採譜中…（端末性能によって長時間かかります）"));
                String json=NativeBridge.nativeAnalyze(modelFile.getAbsolutePath(),audio,AudioDecoder.SAMPLE_RATE,2000);
                List<NoteEvent> recognized=TranscriptionParser.parse(json);
                if(recognized.isEmpty())throw new IllegalStateException("音符が検出されませんでした");
                ui.post(()->{
                    comparison.reset();
                    notes.clear();notes.addAll(recognized);page=0;filter=null;
                    saveProject();refresh();message(notes.size()+"音符を検出しました");
                });
            }catch(Throwable t){ui.post(()->message("採譜失敗: "+t.getMessage()));}
            finally{ui.post(()->{working=false;refresh();});}
        });
    }
    private void updateBusy(String detail){
        statusText.setText(detail);progress.setVisibility(working?View.VISIBLE:View.GONE);
        analyzeButton.setEnabled(!working);
    }
    private void refresh(){
        String engine;
        try{engine=NativeBridge.nativeEngineStatus();}catch(Throwable t){engine=t.toString();}
        audioText.setText("楽曲: "+audioName);
        modelText.setText("モデル: "+(modelFile.isFile()?"導入済み ("+(modelFile.length()/1024/1024)+" MiB)":"未導入"));
        if(!working)updateBusy("エンジン: "+engine);
        Set<String> partSet=new LinkedHashSet<>();for(NoteEvent n:notes)partSet.add(n.instrument);
        summary.setText("検出: "+notes.size()+"音符 / "+partSet.size()+"パート   |   BPM "+bpm+"・4/4（手動設定）");
        List<String> keys=new ArrayList<>(partSet);
        List<String> opts=new ArrayList<>();opts.add("全パート");
        for(String name:keys)opts.add(Instruments.display(name));
        int selectedPart=filter==null?0:keys.indexOf(filter)+1;
        if(selectedPart<0){filter=null;selectedPart=0;}
        partSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,opts));
        partSpinner.setSelection(selectedPart);
        redrawScore();redrawList();
        updateComparisonUi();
    }
    private List<String> availableParts(){
        Set<String> set=new LinkedHashSet<>();for(NoteEvent n:notes)set.add(n.instrument);return new ArrayList<>(set);
    }
    private List<NoteEvent> visibleNotes(){
        List<NoteEvent> result=new ArrayList<>();for(NoteEvent n:notes)if(filter==null||filter.equals(n.instrument))result.add(n);
        result.sort(Comparator.comparingDouble((NoteEvent n)->n.startSeconds));return result;
    }
    private void redrawScore(){if(score!=null){score.setData(notes,filter,bpm,page);prevButton.setEnabled(page>0);}}
    private void redrawList(){
        if(noteList==null)return;
        List<String> lines=new ArrayList<>();for(NoteEvent n:visibleNotes())lines.add(n.describe());
        noteList.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,lines));
    }
    private void editBpm(){
        EditText input=new EditText(this);input.setInputType(2);input.setText(String.valueOf(bpm));
        new AlertDialog.Builder(this).setTitle("BPM (20〜300)").setView(input)
         .setPositiveButton("適用",(d,w)->{
             try{int value=Integer.parseInt(input.getText().toString());
                 if(value<20||value>300)throw new NumberFormatException();
                 bpm=value;comparison.reset();saveProject();refresh();
             }catch(NumberFormatException e){message("BPMは20〜300で指定してください");}
         }).setNegativeButton("取消",null).show();
    }
    private void editNote(NoteEvent n){
        LinearLayout form=vertical();form.setPadding(dp(18),0,dp(18),0);
        EditText pitch=new EditText(this);pitch.setInputType(2);pitch.setText(String.valueOf(n.pitch));
        EditText start=new EditText(this);start.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);start.setText(String.format(Locale.US,"%.3f",n.startSeconds));
        EditText end=new EditText(this);end.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);end.setText(String.format(Locale.US,"%.3f",n.endSeconds));
        form.addView(text("MIDI音高(0〜127)",13));form.addView(pitch);
        form.addView(text("開始秒",13));form.addView(start);
        form.addView(text("終了秒",13));form.addView(end);
        new AlertDialog.Builder(this).setTitle(Instruments.display(n.instrument)).setView(form)
          .setPositiveButton("更新",(d,w)->{
              try{
                  int p=Integer.parseInt(pitch.getText().toString());
                  double a=Double.parseDouble(start.getText().toString());
                  double b=Double.parseDouble(end.getText().toString());
                  if(p<0||p>127||a<0||!Double.isFinite(a)||!Double.isFinite(b)||b<=a)throw new IllegalArgumentException();
                  n.pitch=p;n.startSeconds=a;n.endSeconds=b;notes.sort(Comparator.comparingDouble(x->x.startSeconds));
                  comparison.reset();saveProject();refresh();
              }catch(Exception e){message("入力値が不正です");}
          }).setNeutralButton("削除",(d,w)->{notes.remove(n);comparison.reset();saveProject();refresh();})
          .setNegativeButton("取消",null).show();
    }
    private void settings(){
        String info="MuScriptor Score v"+BuildConfig.VERSION_NAME+"\n\n完全オフライン動作。MP3は外部送信されません。\n\n推論: MuScriptor Small / audio.cpp C API (CPU)\n"+
        "モデル: CC BY-NC 4.0（非商用）\nMuScriptorソース: MIT\naudio.cpp: Apache 2.0\n"+
        "楽譜表示は簡易プレビュー、MusicXML出力は4/4固定です。\n\n"+
        "開発: DEPLOY & RETRIEVE";
        new AlertDialog.Builder(this).setTitle("MuScriptor Score v" + BuildConfig.VERSION_NAME).setMessage(info)
            .setPositiveButton("閉じる",null).show();
    }

    /** Original/MIDI simultaneous playback, with independent gain and mute. */
    private void buildComparisonUi(LinearLayout root){
        root.addView(text("原盤 ↔ MIDI 聴き比べ（現在選択中のパートを再生）",15));

        LinearLayout controls=row(root);
        comparePlayButton=button("▶ 同時再生",this::toggleComparisonPlayback);
        compareStopButton=button("■ 停止",()->{
            if(comparison!=null){
                comparison.pause();
                comparison.seekTo(0);
                updateComparisonUi();
            }
        });
        put(controls,comparePlayButton);
        put(controls,compareStopButton);

        compareTime=text("00:00 / 00:00",13);
        root.addView(compareTime);
        compareSeekBar=new SeekBar(this);
        compareSeekBar.setMax(1);
        compareSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar bar){seekingComparison=true;}
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser){
                if(fromUser)compareTime.setText(formatClock(value)+" / "+formatClock(comparison.getDurationMs()));
            }
            @Override public void onStopTrackingTouch(SeekBar bar){
                seekingComparison=false;
                comparison.seekTo(bar.getProgress());
                updateComparisonUi();
            }
        });
        root.addView(compareSeekBar);

        LinearLayout originalRow=row(root);
        originalVolumeLabel=text("原盤 100%",13);
        originalRow.addView(originalVolumeLabel,new LinearLayout.LayoutParams(0,dp(48),1));
        originalMuteButton=button("原盤を消音",()->{
            originalMuted=!originalMuted;
            applyComparisonVolumes();
        });
        originalRow.addView(originalMuteButton,new LinearLayout.LayoutParams(dp(144),dp(48)));
        originalVolumeSeek=new SeekBar(this);originalVolumeSeek.setMax(100);originalVolumeSeek.setProgress(originalVolume);
        originalVolumeSeek.setOnSeekBarChangeListener(new GainChangeListener(true));
        root.addView(originalVolumeSeek);

        LinearLayout midiRow=row(root);
        midiVolumeLabel=text("MIDI 100%",13);
        midiRow.addView(midiVolumeLabel,new LinearLayout.LayoutParams(0,dp(48),1));
        midiMuteButton=button("MIDIを消音",()->{
            midiMuted=!midiMuted;
            applyComparisonVolumes();
        });
        midiRow.addView(midiMuteButton,new LinearLayout.LayoutParams(dp(144),dp(48)));
        midiVolumeSeek=new SeekBar(this);midiVolumeSeek.setMax(100);midiVolumeSeek.setProgress(midiVolume);
        midiVolumeSeek.setOnSeekBarChangeListener(new GainChangeListener(false));
        root.addView(midiVolumeSeek);

        compareStatus=text("採譜完了後に聴き比べできます。",12);
        root.addView(compareStatus);
    }

    private final class GainChangeListener implements SeekBar.OnSeekBarChangeListener {
        private final boolean forOriginal;
        GainChangeListener(boolean forOriginal){this.forOriginal=forOriginal;}
        @Override public void onStartTrackingTouch(SeekBar bar){}
        @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser){
            if(!fromUser)return;
            if(forOriginal)originalVolume=value;
            else midiVolume=value;
            applyComparisonVolumes();
        }
        @Override public void onStopTrackingTouch(SeekBar bar){
            getPreferences(0).edit().putInt("original_volume",originalVolume)
                    .putInt("midi_volume",midiVolume).apply();
        }
    }

    private void applyComparisonVolumes(){
        if(comparison==null)return;
        comparison.setVolumes(originalMuted?0f:originalVolume/100f,
                              midiMuted?0f:midiVolume/100f);
        updateComparisonUi();
    }

    private void toggleComparisonPlayback(){
        if(audioUri==null){message("原盤となる音声を選択してください");return;}
        List<NoteEvent> selected=visibleNotes();
        if(selected.isEmpty()){message("比較するMIDI音符がありません");return;}
        try{
            if(!comparison.hasSource()){
                comparison.loadAndPlay(audioUri,selected,bpm);
            }else if(comparison.isPlaying()){
                comparison.pause();
            }else{
                comparison.play();
            }
        }catch(Exception e){
            message("聴き比べの準備に失敗: "+e.getMessage());
        }
        updateComparisonUi();
    }

    private static String formatClock(int milliseconds){
        int seconds=Math.max(0,milliseconds)/1000;
        return String.format(Locale.US,"%02d:%02d",seconds/60,seconds%60);
    }

    private void updateComparisonUi(){
        if(comparison==null || comparePlayButton==null)return;
        boolean ready=comparison.isReady();
        boolean loading=comparison.isPreparing();
        comparePlayButton.setText(loading?"読み込み中…":comparison.isPlaying()?"Ⅱ 一時停止":"▶ 同時再生");
        comparePlayButton.setEnabled(!loading && audioUri!=null && !notes.isEmpty());
        compareStopButton.setEnabled(comparison.hasSource());
        int duration=comparison.getDurationMs();
        if(compareSeekBar!=null){
            compareSeekBar.setMax(Math.max(1,duration));
            compareSeekBar.setEnabled(ready);
            if(!seekingComparison)compareSeekBar.setProgress(comparison.getPositionMs());
        }
        if(!seekingComparison && compareTime!=null){
            compareTime.setText(formatClock(comparison.getPositionMs())+" / "+formatClock(duration));
        }
        if(originalVolumeLabel!=null){
            originalVolumeLabel.setText("原盤 "+originalVolume+"%"+(originalMuted?"（消音）":""));
            originalMuteButton.setText(originalMuted?"原盤の消音解除":"原盤を消音");
        }
        if(midiVolumeLabel!=null){
            midiVolumeLabel.setText("MIDI "+midiVolume+"%"+(midiMuted?"（消音）":""));
            midiMuteButton.setText(midiMuted?"MIDIの消音解除":"MIDIを消音");
        }
        if(compareStatus!=null){
            String detail=loading?"原盤とMIDIの再生準備中":
                          comparison.isPlaying()?"同時再生中（簡易同期）":
                          ready?"一時停止中／共通シーク可能":
                          notes.isEmpty()?"採譜完了後に聴き比べできます。":
                          "現在表示中の楽器パートをMIDIにして再生します。";
            compareStatus.setText(detail);
        }
    }

    private void saveAs(String type){
        if(notes.isEmpty()){message("採譜データがありません");return;}
        exportKind=type;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type.equals("midi")?"audio/midi":type.equals("json")?"application/json":"application/vnd.recordare.musicxml+xml");
        String suffix=type.equals("midi")?".mid":type.equals("json")?".json":".musicxml";
        intent.putExtra(Intent.EXTRA_TITLE,"MuScriptorScore"+suffix);
        startActivityForResult(intent,EXPORT);
    }
    private void exportFile(Uri uri){
        if(exportKind==null)return;
        try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){
            if(out==null)throw new IllegalStateException("保存先を開けません");
            byte[] bytes;
            List<NoteEvent> selected=visibleNotes();
            switch(exportKind){
                case "midi":bytes=MidiExporter.write(selected,bpm);break;
                case "json":bytes=TranscriptionParser.serialize(selected).getBytes(StandardCharsets.UTF_8);break;
                default:bytes=MusicXmlExporter.write(selected,bpm,audioName).getBytes(StandardCharsets.UTF_8);
            }
            out.write(bytes);message("保存しました: "+bytes.length+" バイト");
        }catch(Exception e){message("保存に失敗: "+e.getMessage());}
    }
    private void message(String str){
        android.widget.Toast.makeText(this,str,android.widget.Toast.LENGTH_LONG).show();
        statusText.setText(str);
    }
    private void saveProject(){
        try{
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "project.json"))) {
                out.write(TranscriptionParser.serialize(notes).getBytes(StandardCharsets.UTF_8));
            }
            getPreferences(0).edit().putInt("bpm",bpm).apply();
        }catch(Exception e){message("編集結果を保存できませんでした: "+e.getMessage());}
    }
    private void loadProject(){
        bpm=getPreferences(0).getInt("bpm",120);
        audioName=getPreferences(0).getString("name","未選択");
        String previous=getPreferences(0).getString("audio",null);
        if(previous!=null)try{audioUri=Uri.parse(previous);}catch(Exception ignored){}
        File file=new File(getFilesDir(),"project.json");
        if (file.isFile()) {
            try (InputStream in = new java.io.FileInputStream(file);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
                notes.addAll(TranscriptionParser.deserialize(
                    new String(out.toByteArray(), StandardCharsets.UTF_8)));
            } catch (Exception ignored) {}
        }
    }
    @Override protected void onPause(){
        if(comparison!=null)comparison.pause();
        super.onPause();
    }
    @Override protected void onDestroy(){
        ui.removeCallbacks(compareTicker);
        if(comparison!=null)comparison.reset();
        jobs.shutdown();
        super.onDestroy();
    }
}
