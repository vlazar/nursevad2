package com.example.nursevad;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.*;

public class SpeakerVerifier {
    private static SpeakerVerifier instance;
    private Context appContext;
    private OrtEnvironment env;
    private OrtSession session;
    
    private float[][] poiEmbeddings;
    private float[][] poniEmbeddings;
    private String currentFolderUri;

    private static final int SR = 16000;
    private static final int N_FFT = 400;
    private static final int HOP = 160;
    private static final int N_MELS = 40;
    private static final int PARTIAL_FRAMES = 160;

    public static synchronized SpeakerVerifier getInstance(Context context) {
        if (instance == null) instance = new SpeakerVerifier(context.getApplicationContext());
        return instance;
    }

    private SpeakerVerifier(Context context) {
        this.appContext = context;
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(1);
            opts.setInterOpNumThreads(1);
            opts.addCPU(true);
            String modelPath = copyAssetToCache("resemblyzer.onnx");
            if (modelPath != null) session = env.createSession(modelPath, opts);
        } catch (Exception e) {
            Log.e("SpeakerVerifier", "Init failed", e);
        }
    }

    public void loadEmbeddings(String folderUriStr) {
        if (folderUriStr == null || folderUriStr.equals(currentFolderUri)) return;
        currentFolderUri = folderUriStr;
        poiEmbeddings = null;
        poniEmbeddings = null;

        DocumentFile root = DocumentFile.fromTreeUri(appContext, Uri.parse(folderUriStr));
        if (root == null) return;

        List<float[]> poiList = new ArrayList<>();
        List<float[]> poniList = new ArrayList<>();

        DocumentFile poiDir = root.findFile("POI");
        if (poiDir != null && poiDir.isDirectory()) {
            for (DocumentFile f : poiDir.listFiles()) {
                if (f.getName().endsWith(".npy")) poiList.add(readNpy(f));
            }
        }

        DocumentFile poniDir = root.findFile("PONI");
        if (poniDir != null && poniDir.isDirectory()) {
            for (DocumentFile f : poniDir.listFiles()) {
                if (f.getName().endsWith(".npy")) poniList.add(readNpy(f));
            }
        }

        if (!poiList.isEmpty()) poiEmbeddings = flattenList(poiList);
        if (!poniList.isEmpty()) poniEmbeddings = flattenList(poniList);
        Log.d("SpeakerVerifier", "Loaded POI: " + (poiEmbeddings != null ? poiEmbeddings.length : 0) + 
              ", PONI: " + (poniEmbeddings != null ? poniEmbeddings.length : 0));
    }

    private float[][] flattenList(List<float[]> list) {
        int rows = 0;
        for (float[] arr : list) rows += arr.length;
        float[][] res = new float[rows][256];
        int idx = 0;
        for (float[] arr : list) {
            for (float[] row : arr) res[idx++] = row;
        }
        return res;
    }

    private float[][] readNpy(DocumentFile docFile) {
        try (InputStream in = appContext.getContentResolver().openInputStream(docFile.getUri())) {
            byte[] magic = new byte[6]; in.read(magic);
            in.skip(4); // version + header_len placeholder
            byte[] hl = new byte[2]; in.read(hl);
            int hLen = (hl[0] & 0xFF) | ((hl[1] & 0xFF) << 8);
            byte[] hb = new byte[hLen]; in.read(hb);
            String h = new String(hb);
            
            int p1 = h.indexOf("("), p2 = h.indexOf(")");
            String[] sp = h.substring(p1 + 1, p2).split(",");
            int d1 = Integer.parseInt(sp[0].trim());
            int d2 = sp.length > 1 && !sp[1].trim().isEmpty() ? Integer.parseInt(sp[1].trim()) : 1;
            
            int rows = (d1 == 256 && d2 == 1) ? 1 : d1;
            int cols = 256;

            byte[] data = new byte[rows * cols * 4];
            in.read(data);
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            float[][] res = new float[rows][cols];
            for (int i = 0; i < rows; i++) for (int j = 0; j < cols; j++) res[i][j] = bb.getFloat();
            return res;
        } catch (Exception e) {
            Log.e("SpeakerVerifier", "NPY read error", e);
            return new float[0][256];
        }
    }

    public boolean verify(File wavFile) {
        if (session == null || poiEmbeddings == null) return true; // Fallback to POI if no model/embeddings
        try {
            float[] wav = loadWav(wavFile);
            float[] embed = extractEmbedding(wav);
            
            float maxPos = maxCosineSim(poiEmbeddings, embed);
            boolean isMatch = maxPos >= 0.75f; // Default threshold from script
            
            if (isMatch && poniEmbeddings != null) {
                float maxNeg = maxCosineSim(poniEmbeddings, embed);
                if (maxNeg >= 0.75f) isMatch = false;
            }
            return isMatch;
        } catch (Exception e) {
            Log.e("SpeakerVerifier", "Verify error", e);
            return true;
        }
    }

    private float maxCosineSim(float[][] refs, float[] target) {
        float max = -1;
        float tNorm = norm(target);
        for (float[] ref : refs) {
            float dot = 0;
            for (int i = 0; i < 256; i++) dot += ref[i] * target[i];
            float sim = dot / (norm(ref) * tNorm);
            if (sim > max) max = sim;
        }
        return max;
    }

    private float norm(float[] v) {
        double sum = 0;
        for (float f : v) sum += f * f;
        return (float) Math.sqrt(sum);
    }

    private float[] extractEmbedding(float[] wav) throws Exception {
        float peak = 0;
        for (float f : wav) if (Math.abs(f) > peak) peak = Math.abs(f);
        if (peak > 0) for (int i = 0; i < wav.length; i++) wav[i] /= peak;

        float[] y = new float[wav.length + N_FFT];
        System.arraycopy(wav, 0, y, N_FFT / 2, wav.length);

        float[] window = new float[N_FFT];
        for (int i = 0; i < N_FFT; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N_FFT));
        float[][] melFb = melFilterbank();

        int nFrames = 1 + (y.length - N_FFT) / HOP;
        float[][] melSpec = new float[nFrames][N_MELS];
        for (int i = 0; i < nFrames; i++) {
            float[] frame = new float[N_FFT];
            for (int j = 0; j < N_FFT; j++) frame[j] = y[i * HOP + j] * window[j];
            float[] power = dftPower(frame);
            for (int m = 0; m < N_MELS; m++) {
                double sum = 0;
                for (int k = 0; k < power.length; k++) sum += melFb[m][k] * power[k];
                melSpec[i][m] = (float) sum;
            }
        }

        int hop = (int) (PARTIAL_FRAMES / 1.3f);
        List<float[][]> partials = new ArrayList<>();
        for (int s = 0; s <= melSpec.length - PARTIAL_FRAMES; s += hop) {
            float[][] p = new float[PARTIAL_FRAMES][N_MELS];
            for (int i = 0; i < PARTIAL_FRAMES; i++) p[i] = melSpec[s + i];
            partials.add(p);
        }
        if (partials.isEmpty()) partials.add(melSpec);

        float[][][] batch = new float[partials.size()][PARTIAL_FRAMES][N_MELS];
        for (int i = 0; i < partials.size(); i++) batch[i] = partials.get(i);

        OnnxTensor tensor = OnnxTensor.createTensor(env, batch);
        OrtSession.Result res = session.run(Collections.singletonMap(session.getInputs().get(0).getName(), tensor));
        float[][] out = (float[][]) res.get(0).getValue();
        
        float[] embed = new float[256];
        for (float[] o : out) for (int i = 0; i < 256; i++) embed[i] += o[i];
        for (int i = 0; i < 256; i++) embed[i] /= out.length;
        
        float n = norm(embed);
        if (n > 0) for (int i = 0; i < 256; i++) embed[i] /= n;
        return embed;
    }

    private float[] dftPower(float[] frame) {
        int nFreqs = N_FFT / 2 + 1;
        float[] power = new float[nFreqs];
        for (int k = 0; k < nFreqs; k++) {
            double re = 0, im = 0;
            for (int n = 0; n < N_FFT; n++) {
                double angle = 2 * Math.PI * k * n / N_FFT;
                re += frame[n] * Math.cos(angle);
                im -= frame[n] * Math.sin(angle);
            }
            power[k] = (float) (re * re + im * im);
        }
        return power;
    }

    private float[][] melFilterbank() {
        float fMin = 0, fSp = 200f / 3f, minLogHz = 1000f;
        float minLogMel = (minLogHz - fMin) / fSp;
        float logStep = (float) Math.log(6.4) / 27f;
        int nFreqs = N_FFT / 2 + 1;
        float[] melPts = new float[N_MELS + 2];
        float melMin = hzToMel(fMin), melMax = hzToMel(SR / 2f);
        for (int i = 0; i < melPts.length; i++) melPts[i] = melMin + i * (melMax - melMin) / (N_MELS + 1);
        float[] hzPts = new float[N_MELS + 2];
        for (int i = 0; i < hzPts.length; i++) hzPts[i] = melToHz(melPts[i]);
        float[] fftFreqs = new float[nFreqs];
        for (int i = 0; i < nFreqs; i++) fftFreqs[i] = i * (float) SR / N_FFT;
        
        float[][] w = new float[N_MELS][nFreqs];
        for (int i = 0; i < N_MELS; i++) {
            float fdiff1 = hzPts[i + 1] - hzPts[i];
            float fdiff2 = hzPts[i + 2] - hzPts[i + 1];
            for (int j = 0; j < nFreqs; j++) {
                float lower = (fftFreqs[j] - hzPts[i]) / fdiff1;
                float upper = (hzPts[i + 2] - fftFreqs[j]) / fdiff2;
                w[i][j] = Math.max(0, Math.min(lower, upper));
            }
            float enorm = 2f / (hzPts[i + 2] - hzPts[i]);
            for (int j = 0; j < nFreqs; j++) w[i][j] *= enorm;
        }
        return w;
    }

    private float hzToMel(float hz) {
        float fMin = 0, fSp = 200f / 3f, minLogHz = 1000f;
        float minLogMel = (minLogHz - fMin) / fSp;
        float logStep = (float) Math.log(6.4) / 27f;
        return hz >= minLogHz ? minLogMel + (float) Math.log(hz / minLogHz) / logStep : (hz - fMin) / fSp;
    }

    private float melToHz(float mel) {
        float fMin = 0, fSp = 200f / 3f, minLogHz = 1000f;
        float minLogMel = (minLogHz - fMin) / fSp;
        float logStep = (float) Math.log(6.4) / 27f;
        return mel >= minLogMel ? minLogHz * (float) Math.exp(logStep * (mel - minLogMel)) : fMin + fSp * mel;
    }

    private float[] loadWav(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        in.skip(40); // Skip WAV header
        byte[] data = new byte[(int) f.length() - 44];
        in.read(data);
        short[] shorts = new short[data.length / 2];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
        float[] res = new float[shorts.length];
        for (int i = 0; i < shorts.length; i++) res[i] = shorts[i] / 32768f;
        return res;
    }

    private String copyAssetToCache(String filename) {
        File cacheFile = new File(appContext.getCacheDir(), filename);
        if (!cacheFile.exists()) {
            try (InputStream in = appContext.getAssets().open(filename); OutputStream out = new FileOutputStream(cacheFile)) {
                byte[] buf = new byte[4096]; int read;
                while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            } catch (IOException e) { return null; }
        }
        return cacheFile.getAbsolutePath();
    }
}