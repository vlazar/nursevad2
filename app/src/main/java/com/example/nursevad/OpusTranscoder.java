package com.example.nursevad;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * WAV (16 kHz mono PCM16) → Opus (VBR, target bitrate) in an Ogg container.
 * Uses the platform MediaCodec Opus encoder (c2.android.opus.encoder / OMX.google.opus.encoder)
 * and a minimal RFC 3533 / RFC 7845 compliant Ogg-Opus muxer. No external libraries.
 * Runs synchronously — call from a background thread. Returns null on failure
 * (caller keeps the WAV as fallback).
 */
public class OpusTranscoder {

    private static final int PRE_SKIP = 3140;      // samples @48 kHz discarded by decoder
    private static final int OPUS_RATE = 48000;
    private static final int SRC_RATE = 16000;
    private static final int SERIAL = 0x4F50534E;  // "OPSN"

    public static File transcode(File wavFile, int bitrateKbps) {
        if (wavFile == null || !wavFile.exists()) return null;
        File out = new File(wavFile.getParentFile(),
                wavFile.getName().replaceAll("(?i)\\.wav$", "") + ".opus");

        MediaCodec codec = null;
        try {
            byte[] pcm = readPcm(wavFile);
            if (pcm == null || pcm.length == 0) return null;

            MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SRC_RATE, 1);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024);

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            if (codec == null) {
                DebugLogger.log("OpusTranscoder: no Opus encoder on device");
                return null;
            }
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            List<byte[]> packets = new ArrayList<>();
            List<Long> ptsList = new ArrayList<>();

            int offset = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            final long TIMEOUT_US = 10000;

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(inIdx);
                        int remaining = pcm.length - offset;
                        int size = Math.min(remaining, Math.min(buf.remaining(), 6400)); // 200 ms max chunks
                        if (size > 0) {
                            buf.put(pcm, offset, size);
                            long ptsUs = (long) ((offset / 2) * 1000000L / SRC_RATE);
                            codec.queueInputBuffer(inIdx, 0, size, ptsUs, 0);
                            offset += size;
                        } else {
                            long ptsUs = (long) ((offset / 2) * 1000000L / SRC_RATE);
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIdx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(outIdx);
                        byte[] packet = new byte[info.size];
                        ob.get(packet);
                        packets.add(packet);
                        ptsList.add(info.presentationTimeUs);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }

            if (packets.isEmpty()) {
                DebugLogger.log("OpusTranscoder: encoder produced no packets");
                return null;
            }

            // Granule positions (48 kHz samples, including pre-skip)
            long[] granule = new long[packets.size()];
            for (int i = 0; i < packets.size(); i++) {
                long durUs = (i + 1 < packets.size()) ? (ptsList.get(i + 1) - ptsList.get(i)) : 20000;
                if (durUs <= 0) durUs = 20000;
                granule[i] = PRE_SKIP + ((ptsList.get(i) + durUs) * OPUS_RATE / 1000000L);
            }

            FileOutputStream fos = new FileOutputStream(out);
            // Page 0: OpusHead (BOS)
            ByteBuffer hb = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
            hb.put("OpusHead".getBytes());
            hb.put((byte) 1);            // version
            hb.put((byte) 1);            // channels
            hb.putShort((short) PRE_SKIP);
            hb.putInt(SRC_RATE);         // original sample rate (informational)
            hb.putShort((short) 0);      // output gain
            hb.put((byte) 0);            // mapping family
            writePage(fos, hb.array(), 0, 0, 0x02);

            // Page 1: OpusTags
            byte[] vendor = "NurseVAD".getBytes();
            ByteBuffer tb = ByteBuffer.allocate(8 + 4 + vendor.length + 4).order(ByteOrder.LITTLE_ENDIAN);
            tb.put("OpusTags".getBytes());
            tb.putInt(vendor.length);
            tb.put(vendor);
            tb.putInt(0);                // no comments
            writePage(fos, tb.array(), 0, 1, 0x00);

            // Audio pages: one Opus packet per page
            for (int i = 0; i < packets.size(); i++) {
                boolean last = (i == packets.size() - 1);
                writePage(fos, packets.get(i), granule[i], 2 + i, last ? 0x04 : 0x00);
            }
            fos.close();

            if (out.exists() && out.length() > 0) {
                DebugLogger.log("Opus transcode OK: " + out.getName() + " (" + out.length() + " bytes)");
                return out;
            }
            return null;
        } catch (Throwable t) {
            DebugLogger.log("Opus transcode exception: " + t.getMessage());
            if (out.exists()) out.delete();
            return null;
        } finally {
            if (codec != null) {
                try { codec.stop(); codec.release(); } catch (Exception ignored) {}
            }
        }
    }

    // ─── Ogg muxing ───

    private static void writePage(FileOutputStream os, byte[] packet, long granule, int seq, int flags)
            throws java.io.IOException {
        List<Integer> laces = new ArrayList<>();
        int len = packet.length;
        while (len >= 255) { laces.add(255); len -= 255; }
        laces.add(len); // terminates the packet (adds a 0 lacing when needed)

        ByteBuffer page = ByteBuffer.allocate(27 + laces.size() + packet.length).order(ByteOrder.LITTLE_ENDIAN);
        page.put("OggS".getBytes());
        page.put((byte) 0);          // stream structure version
        page.put((byte) flags);      // 0x02 BOS, 0x04 EOS
        page.putLong(granule);
        page.putInt(SERIAL);
        page.putInt(seq);
        page.putInt(0);              // CRC placeholder
        page.put((byte) laces.size());
        for (int l : laces) page.put((byte) l);
        page.put(packet);

        byte[] bytes = page.array();
        int crc = oggCrc(bytes);
        bytes[22] = (byte) (crc & 0xff);
        bytes[23] = (byte) ((crc >> 8) & 0xff);
        bytes[24] = (byte) ((crc >> 16) & 0xff);
        bytes[25] = (byte) ((crc >> 24) & 0xff);
        os.write(bytes);
    }

    private static int oggCrc(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= (b & 0xff) << 24;
            for (int i = 0; i < 8; i++) {
                crc = ((crc & 0x80000000) != 0) ? (crc << 1) ^ 0x04c11db7 : (crc << 1);
            }
        }
        return crc;
    }

    // ─── WAV parsing (robust: locates the "data" chunk) ───

    private static byte[] readPcm(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] riff = new byte[12];
            raf.readFully(riff);
            while (raf.getFilePointer() < raf.length()) {
                byte[] ch = new byte[8];
                raf.readFully(ch);
                int size = ByteBuffer.wrap(ch, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                String id = new String(ch, 0, 4);
                if (id.equals("data")) {
                    byte[] data = new byte[size];
                    raf.readFully(data);
                    return data;
                } else {
                    raf.skipBytes(size + (size & 1));
                }
            }
        } catch (Exception e) {
            DebugLogger.log("readPcm error: " + e.getMessage());
        }
        return null;
    }
}