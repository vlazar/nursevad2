package com.example.nursevad;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * Concatenates member speech WAVs (16 kHz mono 16-bit) into one stitched WAV,
 * inserting silence of exactly the real gap between events
 * (gap = next.speechStartMs - prev.speechEndMs, clamped to [0, 60s] as a
 * sanity bound for crash-resume timestamp anomalies only).
 */
public class SpeechStitcher {

    private static final long MAX_GAP_MS = 60000;
    private static final int BYTES_PER_MS = 32; // 16000 Hz * 2 bytes / 1000 ms

    public static boolean buildStitchedWav(List<TelegramOutbox.Item> members, File out) {
        FileOutputStream fos = null;
        long totalData = 0;
        try {
            fos = new FileOutputStream(out);
            fos.write(new byte[44]); // placeholder header, patched at the end

            byte[] buf = new byte[65536];
            byte[] zero = new byte[65536];
            long prevEnd = 0;

            for (int i = 0; i < members.size(); i++) {
                TelegramOutbox.Item m = members.get(i);

                if (i > 0) {
                    long gapMs = m.speechStartMs - prevEnd;
                    if (gapMs < 0) gapMs = 0;
                    if (gapMs > MAX_GAP_MS) {
                        DebugLogger.log("SpeechStitcher: gap clamped from " + gapMs + "ms to " + MAX_GAP_MS + "ms");
                        gapMs = MAX_GAP_MS;
                    }
                    long gapBytes = gapMs * BYTES_PER_MS;
                    while (gapBytes > 0) {
                        int n = (int) Math.min(zero.length, gapBytes);
                        fos.write(zero, 0, n);
                        gapBytes -= n;
                        totalData += n;
                    }
                }

                FileInputStream in = new FileInputStream(m.wavPath);
                try {
                    long skipped = 0;
                    while (skipped < 44) {
                        long s = in.skip(44 - skipped);
                        if (s <= 0) break;
                        skipped += s;
                    }
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        fos.write(buf, 0, r);
                        totalData += r;
                    }
                } finally {
                    in.close();
                }
                prevEnd = m.speechEndMs;
            }
            fos.close();
            fos = null;

            // Patch RIFF/WAVE header
            RandomAccessFile raf = new RandomAccessFile(out, "rw");
            long chunkSize = totalData + 36;
            int byteRate = 16000 * 1 * 16 / 8;
            raf.seek(0);
            byte[] h = new byte[44];
            h[0]='R'; h[1]='I'; h[2]='F'; h[3]='F';
            h[4]=(byte)(chunkSize & 0xff); h[5]=(byte)((chunkSize>>8)&0xff); h[6]=(byte)((chunkSize>>16)&0xff); h[7]=(byte)((chunkSize>>24)&0xff);
            h[8]='W'; h[9]='A'; h[10]='V'; h[11]='E';
            h[12]='f'; h[13]='m'; h[14]='t'; h[15]=' ';
            h[16]=16; h[17]=0; h[18]=0; h[19]=0;
            h[20]=1; h[21]=0;
            h[22]=1; h[23]=0;                       // mono
            h[24]=(byte)(16000 & 0xff); h[25]=(byte)((16000>>8)&0xff); h[26]=(byte)((16000>>16)&0xff); h[27]=(byte)((16000>>24)&0xff);
            h[28]=(byte)(byteRate & 0xff); h[29]=(byte)((byteRate>>8)&0xff); h[30]=(byte)((byteRate>>16)&0xff); h[31]=(byte)((byteRate>>24)&0xff);
            h[32]=2; h[33]=0;                       // block align
            h[34]=16; h[35]=0;                      // bits per sample
            h[36]='d'; h[37]='a'; h[38]='t'; h[39]='a';
            h[40]=(byte)(totalData & 0xff); h[41]=(byte)((totalData>>8)&0xff); h[42]=(byte)((totalData>>16)&0xff); h[43]=(byte)((totalData>>24)&0xff);
            raf.write(h);
            raf.close();
            return true;
        } catch (Exception e) {
            DebugLogger.log("SpeechStitcher failed: " + e);
            if (fos != null) { try { fos.close(); } catch (Exception ignored) {} }
            if (out.exists()) out.delete();
            return false;
        }
    }
}