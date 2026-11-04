import com.deployandretrieve.muscriptorscore.*;
import java.util.*;
import javax.xml.parsers.*;
import org.xml.sax.InputSource;
import java.io.*;

public class ExporterSmokeTest {
    public static void main(String[] args) throws Exception {
        List<NoteEvent> notes=Arrays.asList(
            new NoteEvent("acoustic_piano",60,0.00,0.5),
            new NoteEvent("acoustic_piano",64,0.00,0.8),
            new NoteEvent("electric_bass",40,0.50,1.5),
            new NoteEvent("drums",36,0.00,0.2));
        byte[] midi=MidiExporter.write(notes,120);
        if(midi.length<60||!new String(midi,0,4).equals("MThd"))throw new AssertionError("bad MIDI header");
        String xml=MusicXmlExporter.write(notes,120,"Smoke & <score>");
        if(!xml.contains("&amp; &lt;score&gt;"))throw new AssertionError("bad XML escaping");
        var db=DocumentBuilderFactory.newInstance().newDocumentBuilder();
        var dom=db.parse(new InputSource(new StringReader(xml)));
        if(dom.getElementsByTagName("part").getLength()!=3)throw new AssertionError("bad track count");
        if(dom.getElementsByTagName("backup").getLength()==0)throw new AssertionError("polyphony not separated");
        System.out.println("PASS: MIDI "+midi.length+" bytes, MusicXML "+xml.length()+" chars");
    }
}
