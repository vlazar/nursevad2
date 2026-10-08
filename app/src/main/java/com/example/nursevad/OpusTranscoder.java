package com.example.nursevad;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Transcodes 16 kHz mono PCM WAV → Opus (16 kHz, 32 kbps VBR) in an OGG container,
 * using ONLY the platform MediaCodec Opus encoder (no extra dependencies).
 * The OGG muxing is hand-rolled per RFC 3533 (Ogg pages) and RFC 7845 (Opus-in-Ogg).
 */
public class OpusTranscoder {

    private static final String TAG = "OpusTranscoder";
    private static final int SAMPLE_RATE = 16000;
    private static final int BIT_RATE = 32000;
    private static final int FRAME_MS = 20;                                  // 20 ms Opus frames
    private static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000;  // 320 samples
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;                // 16-bit mono
    private static final int PRE_SKIP = 312;                                 // libopus lookahead @48k

    public static boolean isAvailable() {
        MediaCodec c = null;
        try {
            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) { try { c.release(); } catch (Throwable ignored) {} }
        }
    }

    /** @return true on success (OGG written); false on any failure (partial OGG deleted). */
    public static boolean transcodeWavToOpusOgg(File wav, File ogg) {
        MediaCodec codec = null;
        OggOpusWriter writer = null;
        try {
            byte[] pcm = readPcm(wav);
            if (pcm == null || pcm.length == 0) return false;

            MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, 2); // 2 = VBR
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES);

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            writer = new OggOpusWriter(ogg, PRE_SKIP, SAMPLE_RATE);

            boolean inputEos = false;
            boolean outputEos = false;
            int feedPos = 0;
            long frameIndex = 0;

            while (!outputEos) {
                if (!inputEos) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(inIdx);
                        buf.clear();
                        int remaining = pcm.length - feedPos;
                        if (remaining <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0,
                                    frameIndex * FRAME_MS * 1000L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            int len = Math.min(FRAME_BYTES, remaining);
                            byte[] frame = new byte[FRAME_BYTES]; // zero-padded last frame
                            System.arraycopy(pcm, feedPos, frame, 0, len);
                            buf.put(frame, 0, FRAME_BYTES);
                            codec.queueInputBuffer(inIdx, 0, FRAME_BYTES,
                                    frameIndex * FRAME_MS * 1000L, 0);
                            feedPos += len;
                            frameIndex++;
                        }
                    }
                }

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    continue;
                } else if (outIdx >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    byte[] packet = new byte[info.size];
                    outBuf.position(info.offset);
                    outBuf.get(packet, 0, info.size);
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    long granule = Math.max(0,
                            PRE_SKIP + (info.presentationTimeUs + FRAME_MS * 1000L) * 48L / 1000L);
                    writer.writePacket(packet, granule, eos);
                    codec.releaseOutputBuffer(outIdx, false);
                    if (eos) outputEos = true;
                }
            }
            writer.finish();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Transcode failed", t);
            if (ogg != null && ogg.exists()) ogg.delete();
            return false;
        } finally {
            if (codec != null) { try { codec.stop(); codec.release(); } catch (Throwable ignored) {} }
            if (writer != null) { try { writer.close(); } catch (Throwable ignored) {} }
        }
    }

    private static byte[] readPcm(File wav) throws IOException {
        FileInputStream in = new FileInputStream(wav);
        try {
            byte[] header = new byte[44];
            int read = 0;
            while (read < 44) {
                int r = in.read(header, read, 44 - read);
                if (r < 0) break;
                read += r;
            }
            if (read < 44) return null;
            byte[] rest = new byte[(int) Math.max(0, wav.length() - 44)];
            read = 0;
            while (read < rest.length) {
                int r = in.read(rest, read, rest.length - read);
                if (r < 0) break;
                read += r;
            }
            return rest;
        } finally {
            in.close();
        }
    }

    // ─── Minimal Ogg (RFC 3533) / OpusHead+OpusTags (RFC 7845) writer ───
    private static class OggOpusWriter {
        private final OutputStream out;
        private final int serial;
        private final int preSkip;
        private final int sampleRate;
        private int pageSeq = 0;
        private boolean headersWritten = false;

        OggOpusWriter(File file, int preSkip, int sampleRate) throws IOException {
            this.out = new FileOutputStream(file);
            this.preSkip = preSkip;
            this.sampleRate = sampleRate;
            this.serial = new Random().nextInt();
        }

        void writePacket(byte[] packet, long granule, boolean eos) throws IOException {
            if (!headersWritten) {
                writePage(opusHead(), 0, 2); // BOS
                writePage(opusTags(), 0, 0);
                headersWritten = true;
            }
            writePage(packet, granule, eos ? 4 : 0);
        }

        void finish() throws IOException { out.flush(); }
        void close() throws IOException { out.close(); }

        private byte[] opusHead() {
            ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
            b.put("OpusHead".getBytes());
            b.put((byte) 1);          // version
            b.put((byte) 1);          // channels
            b.putShort((short) preSkip);
            b.putInt(sampleRate);     // original sample rate (informational)
            b.putShort((short) 0);    // output gain
            b.put((byte) 0);          // mapping family
            return b.array();
        }

        private byte[] opusTags() {
            byte[] vendor = "NurseVAD".getBytes();
            ByteBuffer b = ByteBuffer.allocate(8 + 4 + vendor.length + 4).order(ByteOrder.LITTLE_ENDIAN);
            b.put("OpusTags".getBytes());
            b.putInt(vendor.length);
            b.put(vendor);
            b.putInt(0);              // no user comments
            return b.array();
        }

        private void writePage(byte[] packet, long granule, int type) throws IOException {
            int nseg = (packet.length / 255) + 1;
            byte[] page = new byte[27 + nseg + packet.length];
            ByteBuffer h = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
            h.put("OggS".getBytes());
            h.put((byte) 0);          // version
            h.put((byte) type);       // 2=BOS, 4=EOS
            h.putLong(granule);
            h.putInt(serial);
            h.putInt(pageSeq++);
            h.putInt(0);              // CRC placeholder
            h.put((byte) nseg);
            int rem = packet.length;
            for (int i = 0; i < nseg; i++) {
                int v = Math.min(255, rem);
                h.put((byte) v);
                rem -= v;
            }
            System.arraycopy(packet, 0, page, 27 + nseg, packet.length);
            int crc = crc32(page);
            page[22] = (byte) (crc & 0xff);
            page[23] = (byte) ((crc >> 8) & 0xff);
            page[24] = (byte) ((crc >> 16) & 0xff);
            page[25] = (byte) ((crc >> 24) & 0xff);
            out.write(page);
        }
    }

    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = ((r & 0x80000000) != 0) ? (r << 1) ^ 0x04c11db7 : r << 1;
            }
            CRC_TABLE[i] = r;
        }
    }
    private static int crc32(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ (b & 0xff)) & 0xff];
        }
        return crc;
    }
}