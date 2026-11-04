package com.deployandretrieve.muscriptorscore;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Instruments {
    private Instruments() {}
    private static final String[] LABELS = {
      "acoustic_piano|ピアノ|0", "electric_piano|エレクトリックピアノ|2",
      "chromatic_percussion|鍵盤打楽器|11", "organ|オルガン|19",
      "acoustic_guitar|アコースティックギター|24", "clean_electric_guitar|エレキギター|27",
      "distorted_electric_guitar|ディストーションギター|30",
      "acoustic_bass|アコースティックベース|32", "electric_bass|エレキベース|33",
      "violin|バイオリン|40", "viola|ビオラ|41", "cello|チェロ|42",
      "contrabass|コントラバス|43", "orchestral_harp|ハープ|46", "timpani|ティンパニ|47",
      "string_ensemble|弦楽アンサンブル|48", "synth_strings|シンセストリングス|50",
      "voice|ボーカル|53", "orchestra_hit|オーケストラヒット|55",
      "trumpet|トランペット|56", "trombone|トロンボーン|57", "tuba|チューバ|58",
      "french_horn|ホルン|60", "brass_section|ブラス|61",
      "soprano_and_alto_sax|サックス(高音域)|64", "tenor_sax|テナーサックス|66",
      "baritone_sax|バリトンサックス|67", "oboe|オーボエ|68",
      "english_horn|イングリッシュホルン|69", "bassoon|ファゴット|70",
      "clarinet|クラリネット|71", "flutes|フルート|73",
      "synth_lead|シンセリード|80", "synth_pad|シンセパッド|88", "drums|ドラム|0"
    };
    private static final Map<String,String> names=new LinkedHashMap<>();
    private static final Map<String,Integer> programs=new LinkedHashMap<>();
    static {for(String s:LABELS){String[] a=s.split("\\|");names.put(a[0],a[1]);programs.put(a[0],Integer.parseInt(a[2]));}}
    public static String display(String key){return names.getOrDefault(key,key.replace('_',' '));}
    public static int program(String key){return programs.getOrDefault(key,0);}
    public static boolean isBass(String key){return key.contains("bass")||key.equals("cello")||key.equals("contrabass")||key.equals("tuba")||key.equals("bassoon");}
    public static String pitchName(int pitch){
        final String[] names={"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
        return names[Math.floorMod(pitch,12)]+(Math.floorDiv(pitch,12)-1);
    }
}
