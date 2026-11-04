package com.deployandretrieve.muscriptorscore;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Local-only Android MediaCodec decode + linear resampling to 16 kHz mono. */
public final class AudioDecoder {
    public static final int SAMPLE_RATE = 16_000;
    private static final int MAX_SECONDS = 10 * 60;
    private AudioDecoder() { }

    public static float[] decode(Context ctx, Uri uri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(ctx, uri, null);
            MediaFormat format = null;
            int track = -1;
            for (int i=0; i<extractor.getTrackCount(); i++) {
                MediaFormat f=extractor.getTrackFormat(i);
                if (f.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {track=i; format=f; break;}
            }
            if(track<0)throw new IOException("音声トラックがありません");
            if(format.containsKey(MediaFormat.KEY_DURATION)
                    && format.getLong(MediaFormat.KEY_DURATION)> MAX_SECONDS * 1_000_000L)
                throw new IOException("v1.0 は10分以内の曲に対応します");
            extractor.selectTrack(track);
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            codec.configure(format,null,null,0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            FloatBuilder output = new FloatBuilder(256_000);
            Resampler resampler = new Resampler();
            int sampleRate=format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44_100;
            int channels=format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int pcmEncoding=AudioFormat.ENCODING_PCM_16BIT;
            boolean inputDone=false,outputDone=false;
            int emptyPolls=0;
            while(!outputDone) {
                if(!inputDone) {
                    int ix=codec.dequeueInputBuffer(10_000);
                    if(ix>=0) {
                        ByteBuffer buffer=codec.getInputBuffer(ix);
                        if(buffer==null)throw new IOException("Decoder input buffer missing");
                        buffer.clear();
                        int count=extractor.readSampleData(buffer,0);
                        if(count<0) {
                            codec.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone=true;
                        } else {
                            codec.queueInputBuffer(ix,0,count,extractor.getSampleTime(),0);
                            extractor.advance();
                        }
                    }
                }
                int ix=codec.dequeueOutputBuffer(info,10_000);
                if(ix==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out=codec.getOutputFormat();
                    if(out.containsKey(MediaFormat.KEY_SAMPLE_RATE))sampleRate=out.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if(out.containsKey(MediaFormat.KEY_CHANNEL_COUNT))channels=out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if(out.containsKey(MediaFormat.KEY_PCM_ENCODING))pcmEncoding=out.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    if(channels<1 || channels>16)throw new IOException("Unsupported channel count");
                } else if(ix>=0) {
                    ByteBuffer data=codec.getOutputBuffer(ix);
                    if(data!=null && info.size>0) {
                        data.position(info.offset);
                        data.limit(info.offset+info.size);
                        ByteBuffer pcm=data.slice().order(ByteOrder.LITTLE_ENDIAN);
                        int sampleBytes=pcmEncoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;
                        int frames=pcm.remaining()/(sampleBytes*channels);
                        for(int f=0;f<frames;f++) {
                            float value=0f;
                            for(int ch=0;ch<channels;ch++) {
                                value += pcmEncoding==AudioFormat.ENCODING_PCM_FLOAT
                                        ?pcm.getFloat():pcm.getShort()/32768f;
                            }
                            resampler.add(value/channels,sampleRate,output);
                        }
                    }
                    outputDone=(info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    codec.releaseOutputBuffer(ix,false);
                    emptyPolls=0;
                } else if(++emptyPolls>6000) {
                    throw new IOException("音声デコードがタイムアウトしました");
                }
                if(output.size()>MAX_SECONDS*SAMPLE_RATE)throw new IOException("音声が10分を超えています");
            }
            return output.finish();
        } finally {
            if(codec!=null) {try{codec.stop();}catch(Exception ignored){} codec.release();}
            extractor.release();
        }
    }

    private static final class Resampler {
        private long index=0;
        private double next=0;
        private float prev=0;
        void add(float current,int inputRate,FloatBuilder out) {
            if(index==0) {out.add(current);next=(double)inputRate/SAMPLE_RATE;}
            else {
                while(next<=index) {
                    float factor=(float)(next-(index-1));
                    out.add(prev+(current-prev)*factor);
                    next+=(double)inputRate/SAMPLE_RATE;
                }
            }
            prev=current;index++;
        }
    }
    private static final class FloatBuilder {
        private float[] data;
        private int size;
        FloatBuilder(int capacity){data=new float[capacity];}
        void add(float f){if(size==data.length)data=Arrays.copyOf(data,data.length*2);data[size++]=f;}
        int size(){return size;}
        float[] finish(){return Arrays.copyOf(data,size);}
    }
}
