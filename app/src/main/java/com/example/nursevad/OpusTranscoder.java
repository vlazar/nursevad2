package com.example.nursevad;

import android.content.Context;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import java.io.File;

public class OpusTranscoder {

    /**
     * Transcodes a 16 kHz mono WAV to Opus (VBR, target bitrate kbps) in an Ogg container.
     * Runs synchronously — call from a background thread.
     * Returns the .opus file, or null on failure (caller keeps the WAV as fallback).
     */
    public static File transcode(Context context, File wavFile, int bitrateKbps) {
        if (wavFile == null || !wavFile.exists()) return null;

        File out = new File(wavFile.getParentFile(),
                wavFile.getName().replaceAll("(?i)\\.wav$", "") + ".opus");

        String cmd = "-y -hide_banner -loglevel error"
                + " -i \"" + wavFile.getAbsolutePath() + "\""
                + " -c:a libopus"
                + " -b:a " + bitrateKbps + "k"      // VBR target bitrate
                + " -vbr 1"                          // VBR mode (libopus default, explicit)
                + " -application voip"               // speech-optimized mode
                + " \"" + out.getAbsolutePath() + "\"";

        try {
            FFmpegSession session = FFmpegKit.execute(cmd);
            if (ReturnCode.isSuccess(session.getReturnCode()) && out.exists() && out.length() > 0) {
                DebugLogger.log("Opus transcode OK: " + out.getName() + " (" + out.length() + " bytes)");
                return out;
            }
            DebugLogger.log("Opus transcode FAILED rc=" + session.getReturnCode()
                    + " out=" + session.getOutput());
        } catch (Throwable t) {
            DebugLogger.log("Opus transcode exception: " + t.getMessage());
        }
        return null;
    }
}