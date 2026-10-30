package dev.watchmemory;

import android.media.*;
import java.io.File;
import java.nio.ByteBuffer;
import kotlin.Unit;

/** Shell-only debug entry: synthetic sine wave, never opens the microphone. */
public class CodecProbe {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); root.mkdirs();
        if (args.length > 1 && args[1].equals("DECODE")) { for(File file:root.listFiles()) System.out.println("DECODE " + file + " " + decode(file)); return; }
        for (RecordingEncoding encoding : RecordingEncoding.values()) {
            if (args.length > 1 && !encoding.name().equals(args[1])) continue;
            for (int rate : new int[]{16000, 24000, 48000}) {
                for (int bitrate : encoding.getRates()) {
                    String name = encoding.name() + "-" + rate + "-" + bitrate;
                    File directory = new File(root, name); directory.mkdirs();
                    RecordingWriter writer = encoding == RecordingEncoding.OPUS
                        ? new OpusSegmentWriter(directory, "probe", 1_000_000L, (a,b,c,d,e) -> Unit.INSTANCE)
                        : new SegmentWriter(directory, "probe", 1_000_000L, rate, (a,b,c,d,e) -> Unit.INSTANCE);
                    MediaFormat format = MediaFormat.createAudioFormat(encoding.getMime(), rate, 1);
                    format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, rate * 2 * 128 / 1000);
                    if (encoding == RecordingEncoding.AAC) format.setInteger(MediaFormat.KEY_AAC_PROFILE, 2);
                    String codecName = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
                    if (codecName == null) throw new IllegalStateException("Unsupported " + name);
                    MediaCodec codec = MediaCodec.createByCodecName(codecName);
                    int samples = 0, total = rate * 3;
                    boolean sent = false, done = false;
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    long deadline = System.nanoTime() + 30_000_000_000L;
                    try {
                        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start();
                        while (!done) {
                            if (System.nanoTime() > deadline) throw new IllegalStateException("Timeout " + name);
                            if (!sent) {
                                int slot = codec.dequeueInputBuffer(10000);
                                if (slot >= 0) {
                                    ByteBuffer input = codec.getInputBuffer(slot); input.clear();
                                    int count = Math.min(Math.min(input.remaining()/2, rate * 128 / 1000), total - samples);
                                    for (int i=0;i<count;i++) {
                                        short value = (short)(8000 * Math.sin(2*Math.PI*440*(samples+i)/rate));
                                        input.put((byte)value); input.put((byte)(value >> 8));
                                    }
                                    codec.queueInputBuffer(slot,0,count*2,samples*1_000_000L/rate,count==0?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);
                                    samples += count; sent = count == 0;
                                }
                            }
                            int output = codec.dequeueOutputBuffer(info,10000);
                            if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                MediaFormat actual = codec.getOutputFormat();
                                System.out.println("FORMAT " + actual);
                                ByteBuffer csd=actual.getByteBuffer("csd-0");
                                if(csd!=null){ByteBuffer copy=csd.duplicate();StringBuilder hex=new StringBuilder();while(copy.hasRemaining())hex.append(String.format("%02x",copy.get()));System.out.println("CSD " + hex);}
                                writer.configure(actual);
                            }
                            else if (output >= 0) {
                                try {
                                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                                        ByteBuffer bytes = codec.getOutputBuffer(output);
                                        bytes.position(info.offset); bytes.limit(info.offset+info.size);
                                        byte[] packet = new byte[info.size]; bytes.get(packet);
                                        writer.write(packet,info.presentationTimeUs,packet.length);
                                    }
                                    done = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                                } finally { codec.releaseOutputBuffer(output,false); }
                            }
                        }
                        writer.sync();
                    } finally { try { writer.close(); } finally { codec.release(); } }
                    long decoded = 0;
                    for (File file : directory.listFiles()) { long duration=decode(file); decoded+=duration;System.out.println("DECODE " + file.getName() + " " + duration); }
                    // Allow codec padding but reject lost preroll, wrong clock, or duplicate seconds.
                    if (Math.abs(decoded - 3_000_000L) > 150_000L) throw new IllegalStateException(name + " duration " + decoded);
                    System.out.println("PASS " + name + " encoder=" + codecName + " decoded_us=" + decoded);
                }
            }
        }
    }
    static long decode(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor(); extractor.setDataSource(file.getPath()); extractor.selectTrack(0);
        MediaFormat format = extractor.getTrackFormat(0);
        System.out.println("EXTRACT " + file.getName() + " " + format);
        MediaCodec decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        long bytes = 0; int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        boolean sent=false, done=false;
        int packetCounter=0; long firstPts=Long.MIN_VALUE; long decodedClock=0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline=System.nanoTime()+10_000_000_000L;
        try {
            decoder.configure(format,null,null,0);decoder.start();
            while(!done) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Decode timeout " + file);
                if(!sent) {
                    int slot=decoder.dequeueInputBuffer(10000);
                    if(slot>=0) {
                        ByteBuffer input=decoder.getInputBuffer(slot);input.clear();int n=extractor.readSampleData(input,0);
                        if (n>=0) { packetCounter++; if(firstPts==Long.MIN_VALUE)firstPts=extractor.getSampleTime(); }
                        long pts=decodedClock;
                        if(n>0 && format.getString(MediaFormat.KEY_MIME).equals("audio/opus")) {
                            byte[] packet=new byte[n]; input.position(0); input.get(packet); input.position(0);
                            decodedClock+=OpusPackets.INSTANCE.samples(packet,n)*1_000_000L/48000;
                        } else if(n>=0) pts=extractor.getSampleTime()-firstPts;
                        decoder.queueInputBuffer(slot,0,Math.max(n,0),pts,n<0?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);
                        if(n<0)sent=true;else extractor.advance();
                    }
                }
                int slot=decoder.dequeueOutputBuffer(info,10000);
                if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) rate=decoder.getOutputFormat().getInteger(MediaFormat.KEY_SAMPLE_RATE);
                else if(slot>=0) {bytes+=info.size;done=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;decoder.releaseOutputBuffer(slot,false);}
            }
        } finally {decoder.release();extractor.release();}
        System.out.println("PACKETS " + packetCounter + " firstPts=" + firstPts);
        return bytes*1_000_000L/(rate*2L);
    }
}
