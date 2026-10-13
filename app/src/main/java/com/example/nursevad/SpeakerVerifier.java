package com.example.nursevad;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SpeakerVerifier {
    private static SpeakerVerifier instance;
    private final Context appContext;
    private OrtEnvironment env;
    private OrtSession session;

    private float[][] poiEmbeddings;
    private float[][] poniEmbeddings;
    private String currentFolderUri;

    // ReDimNet2 b0 lm constants (mirror of redimnet2-onnx frontend.py)
    private static final String MODEL_NAME = "redimnet2_b0_lm.onnx";
    private static final int SR = 16000;
    private static final int EMB_DIM = 192;
    private static final int MEL_BINS = 72;
    private static final int FFT_LENGTH = 512;
    private static final int FRAME_LENGTH = 400;
    private static final int FRAME_STEP = 160;
    private static final int PAD = FRAME_STEP / 2;             // 80
    private static final float LOW_FREQ = 20f;
    private static final float HIGH_FREQ = 7600f;
    private static final int MIN_SAMPLES = SR;                 // 1 s
    private static final int MAX_SAMPLES = 30 * SR;            // 30 s
    private static final float EPS = 1e-8f;

    private static float[] hammingWindow;
    private static float[][] melBank;                          // [256][72]

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
            options.addNnapi(); // Uncomment for NNAPI acceleration if supported on your target devices
            String modelPath = copyAssetToCache(MODEL_NAME);
            if (modelPath != null) session = env.createSession(modelPath, opts);
        } catch (Exception e) {
            Log.e("SpeakerVerifier", "Init failed", e);
        }
    }

    // ───────────────────────── Embeddings loading (POI / PONI .npy) ─────────────────────────

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
                if (f.getName() != null && f.getName().endsWith(".npy")) {
                    for (float[] emb : readNpy(f)) {
                        if (emb.length == EMB_DIM) poiList.add(emb);
                        else DebugLogger.log("SpeakerVerifier: skipping POI row with dim " + emb.length);
                    }
                }
            }
        }

        DocumentFile poniDir = root.findFile("PONI");
        if (poniDir != null && poniDir.isDirectory()) {
            for (DocumentFile f : poniDir.listFiles()) {
                if (f.getName() != null && f.getName().endsWith(".npy")) {
                    for (float[] emb : readNpy(f)) {
                        if (emb.length == EMB_DIM) poniList.add(emb);
                        else DebugLogger.log("SpeakerVerifier: skipping PONI row with dim " + emb.length);
                    }
                }
            }
        }

        if (!poiList.isEmpty()) poiEmbeddings = poiList.toArray(new float[0][]);
        if (!poniList.isEmpty()) poniEmbeddings = poniList.toArray(new float[0][]);

        DebugLogger.log("SpeakerVerifier loaded POI=" + (poiEmbeddings != null ? poiEmbeddings.length : 0)
                + " PONI=" + (poniEmbeddings != null ? poniEmbeddings.length : 0));
    }

    private float[][] readNpy(DocumentFile docFile) {
        try (InputStream in = appContext.getContentResolver().openInputStream(docFile.getUri())) {
            byte[] magic = new byte[6]; in.read(magic);
            byte[] version = new byte[2]; in.read(version);
            byte[] hl = new byte[2]; in.read(hl);
            int hLen = (hl[0] & 0xFF) | ((hl[1] & 0xFF) << 8);
            byte[] hb = new byte[hLen]; in.read(hb);
            String h = new String(hb);

            int p1 = h.indexOf("("), p2 = h.indexOf(")");
            String[] sp = h.substring(p1 + 1, p2).split(",");
            List<Integer> dims = new ArrayList<>();
            for (String s : sp) {
                String t = s.trim();
                if (!t.isEmpty()) dims.add(Integer.parseInt(t));
            }
            int rows, cols;
            if (dims.size() == 1) { rows = 1; cols = dims.get(0); }
            else { rows = dims.get(0); cols = dims.get(1); }

            byte[] data = new byte[rows * cols * 4];
            int bytesRead = 0;
            while (bytesRead < data.length) {
                int read = in.read(data, bytesRead, data.length - bytesRead);
                if (read == -1) break;
                bytesRead += read;
            }

            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            float[][] res = new float[rows][cols];
            for (int i = 0; i < rows; i++)
                for (int j = 0; j < cols; j++)
                    res[i][j] = bb.getFloat();
            return res;
        } catch (Exception e) {
            Log.e("SpeakerVerifier", "NPY read error", e);
            return new float[0][];
        }
    }

    // ───────────────────────── Verification (POI / PONI logic unchanged) ─────────────────────────

    public boolean verify(File wavFile) {
        DebugLogger.log("SpeakerVerifier.verify: file=" + wavFile.getAbsolutePath()
                + ", size=" + wavFile.length() + " bytes");
        if (session == null) {
            DebugLogger.log("SpeakerVerifier.verify: session is null, returning true (POI)");
            return true;
        }
        if (poiEmbeddings == null || poiEmbeddings.length == 0) {
            DebugLogger.log("SpeakerVerifier.verify: no POI embeddings loaded, returning true (POI)");
            return true;
        }
        try {
            float[] wav = loadWav(wavFile);
            DebugLogger.log("SpeakerVerifier.verify: loaded " + wav.length + " samples");

            float[] embed = extractEmbedding(wav);
            if (embed == null) {
                DebugLogger.log("SpeakerVerifier.verify: embedding failed, returning true (POI)");
                return true;
            }

            float poiThreshold = SettingsManager.getPoiThreshold(appContext) / 100f;
            float poniThreshold = SettingsManager.getPoniThreshold(appContext) / 100f;

            float maxPos = maxCosineSim(poiEmbeddings, embed);
            DebugLogger.log("SpeakerVerifier.verify: maxPos=" + maxPos + ", poiThreshold=" + poiThreshold);
            boolean isMatch = maxPos >= poiThreshold;

            if (isMatch && poniEmbeddings != null && poniEmbeddings.length > 0) {
                float maxNeg = maxCosineSim(poniEmbeddings, embed);
                DebugLogger.log("SpeakerVerifier.verify: maxNeg=" + maxNeg + ", poniThreshold=" + poniThreshold);
                if (maxNeg >= poniThreshold) {
                    isMatch = false;
                    DebugLogger.log("SpeakerVerifier.verify: PONI veto applied!");
                }
            }
            DebugLogger.log("SpeakerVerifier.verify: final result isMatch=" + isMatch);
            return isMatch;
        } catch (Exception e) {
            DebugLogger.log("SpeakerVerifier.verify: EXCEPTION " + e.getMessage());
            return true;
        }
    }

    private float maxCosineSim(float[][] refs, float[] target) {
        float max = -1;
        float tNorm = norm(target);
        if (tNorm == 0) return -1;
        for (float[] ref : refs) {
            int d = Math.min(ref.length, target.length);
            float dot = 0;
            for (int i = 0; i < d; i++) dot += ref[i] * target[i];
            float rNorm = norm(ref);
            if (rNorm == 0) continue;
            float sim = dot / (rNorm * tNorm);
            if (sim > max) max = sim;
        }
        return max;
    }

    private float norm(float[] v) {
        double sum = 0;
        for (float f : v) sum += f * f;
        return (float) Math.sqrt(sum);
    }

    // ───────────────────────── Embedding extraction (ReDimNet2) ─────────────────────────

    private float[] extractEmbedding(float[] wav) throws Exception {
        List<float[]> embs = new ArrayList<>();
        int pos = 0;
        while (pos < wav.length) {
            int remaining = wav.length - pos;
            int len = Math.min(MAX_SAMPLES, remaining);
            float[] chunk;
            if (len < MIN_SAMPLES) {
                if (embs.isEmpty()) {
                    chunk = new float[MIN_SAMPLES];           // pad very short audio with silence
                    System.arraycopy(wav, pos, chunk, 0, remaining);
                    len = MIN_SAMPLES;
                } else {
                    break;                                     // trailing fragment < 1 s: skip
                }
            } else {
                chunk = Arrays.copyOfRange(wav, pos, pos + len);
            }
            float[] e = embedChunk(chunk);
            if (e != null) embs.add(e);
            pos += len;
        }
        if (embs.isEmpty()) return null;

        float[] avg = new float[EMB_DIM];
        for (float[] e : embs) for (int i = 0; i < EMB_DIM; i++) avg[i] += e[i];
        for (int i = 0; i < EMB_DIM; i++) avg[i] /= embs.size();
        float n = norm(avg);
        if (n > 0) for (int i = 0; i < EMB_DIM; i++) avg[i] /= n;
        return avg;
    }

    private float[] embedChunk(float[] wav) throws Exception {
        float[] features = computeFeatures(wav);              // [72 * T], T % 4 == 0
        int T = features.length / MEL_BINS;
        OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(features),
                new long[]{1, 1, MEL_BINS, T});
        OrtSession.Result result = null;
        try {
            result = session.run(Collections.singletonMap("features", tensor));
            float[][] out = (float[][]) result.get(0).getValue();
            float[] emb = out[0];
            float n = norm(emb);
            if (n > 0) for (int i = 0; i < emb.length; i++) emb[i] /= n;
            return emb;
        } finally {
            tensor.close();
            if (result != null) result.close();
        }
    }

    /** Exact replica of redimnet2-onnx frontend: norm → preemph → STFT → mel → log → time-mean → align4. */
    private float[] computeFeatures(float[] x) {
        int L = x.length;

        // 1. mean/std signal normalization
        double mean = 0;
        for (float v : x) mean += v;
        mean /= L;
        double var = 0;
        for (float v : x) var += (v - mean) * (v - mean);
        var /= L;
        float std = (float) (Math.sqrt(var) + EPS);
        float[] n = new float[L];
        for (int i = 0; i < L; i++) n[i] = (float) ((x[i] - mean) / std);

        // 2. reflect-pad(1) + pre-emphasis [−0.97, 1.0]
        float[] pre = new float[L];
        pre[0] = n[0] - 0.97f * (L > 1 ? n[1] : n[0]);
        for (int i = 1; i < L; i++) pre[i] = n[i] - 0.97f * n[i - 1];

        // 3. STFT: conv1d(kernel 400, stride 160, padding 80) → frame count
        int numFrames = (L + 2 * PAD - FRAME_LENGTH) / FRAME_STEP + 1;
        if (numFrames < 4) throw new IllegalArgumentException("audio too short for ReDimNet2");

        float[] window = window();
        float[] re = new float[FFT_LENGTH];
        float[] im = new float[FFT_LENGTH];
        float[][] power = new float[numFrames][FFT_LENGTH / 2];

        for (int t = 0; t < numFrames; t++) {
            int start = t * FRAME_STEP - PAD;
            Arrays.fill(re, 0);
            Arrays.fill(im, 0);
            for (int k = 0; k < FRAME_LENGTH; k++) {
                int j = start + k;
                re[k] = (j >= 0 && j < L) ? pre[j] * window[k] : 0f;
            }
            fft(re, im);
            for (int b = 0; b < FFT_LENGTH / 2; b++) {
                float p = re[b] * re[b] + im[b] * im[b];
                power[t][b] = Math.max(EPS, Math.min(p, 1f / EPS));
            }
        }

        // 4. mel filterbank (72 bins, 20–7600 Hz)
        float[][] bank = melBank();
        float[][] mel = new float[numFrames][MEL_BINS];
        for (int t = 0; t < numFrames; t++) {
            for (int m = 0; m < MEL_BINS; m++) {
                double s = 0;
                for (int b = 0; b < FFT_LENGTH / 2; b++) s += bank[b][m] * power[t][b];
                mel[t][m] = (float) Math.max(EPS, Math.min(s, 1f / EPS));
            }
        }

        // 5. log + per-bin mean normalization over time
        for (int t = 0; t < numFrames; t++)
            for (int m = 0; m < MEL_BINS; m++)
                mel[t][m] = (float) Math.log(mel[t][m] + EPS);
        for (int m = 0; m < MEL_BINS; m++) {
            double s = 0;
            for (int t = 0; t < numFrames; t++) s += mel[t][m];
            float mu = (float) (s / numFrames);
            for (int t = 0; t < numFrames; t++) mel[t][m] -= mu;
        }

        // 6. align time to multiple of 4, flatten as [1,1,72,T]
        int T = (numFrames / 4) * 4;
        float[] flat = new float[MEL_BINS * T];
        for (int m = 0; m < MEL_BINS; m++)
            for (int t = 0; t < T; t++)
                flat[m * T + t] = mel[t][m];
        return flat;
    }

    private static synchronized float[] window() {
        if (hammingWindow == null) {
            hammingWindow = new float[FRAME_LENGTH];
            for (int i = 0; i < FRAME_LENGTH; i++)
                hammingWindow[i] = (float) (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (FRAME_LENGTH - 1)));
        }
        return hammingWindow;
    }

    private static synchronized float[][] melBank() {
        if (melBank != null) return melBank;
        int bins = FFT_LENGTH / 2;                    // 256
        float[] points = new float[MEL_BINS + 2];
        float mLo = hzToMel(LOW_FREQ), mHi = hzToMel(HIGH_FREQ);
        for (int i = 0; i < points.length; i++)
            points[i] = mLo + i * (mHi - mLo) / (MEL_BINS + 1);
        float[][] bank = new float[bins][MEL_BINS];
        for (int b = 1; b < bins; b++) {              // row 0 stays zero (matches np.vstack zero row)
            float binMel = hzToMel(b * (8000f / 255f));   // np.linspace(0, 8000, 256)[1:] spacing
            for (int m = 0; m < MEL_BINS; m++) {
                float lower = points[m], center = points[m + 1], upper = points[m + 2];
                float v1 = (binMel - lower) / (center - lower);
                float v2 = (upper - binMel) / (upper - center);
                bank[b][m] = Math.max(0f, Math.min(v1, v2));
            }
        }
        melBank = bank;
        return bank;
    }

    private static float hzToMel(float hz) {
        return 2595f * (float) Math.log10(1 + hz / 700f);
    }

    /** In-place radix-2 FFT (power-of-two size). */
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 0; i < n; i++) {
            int j = Integer.reverse(i) >>> (32 - Integer.numberOfTrailingZeros(n));
            if (j > i) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= n; size *= 2) {
            int half = size / 2;
            double ang = -2 * Math.PI / size;
            float wR = (float) Math.cos(ang), wI = (float) Math.sin(ang);
            for (int i = 0; i < n; i += size) {
                float cR = 1, cI = 0;
                for (int j = 0; j < half; j++) {
                    float tR = cR * re[i + j + half] - cI * im[i + j + half];
                    float tI = cR * im[i + j + half] + cI * re[i + j + half];
                    re[i + j + half] = re[i + j] - tR;
                    im[i + j + half] = im[i + j] - tI;
                    re[i + j] += tR;
                    im[i + j] += tI;
                    float nR = cR * wR - cI * wI;
                    cI = cR * wI + cI * wR;
                    cR = nR;
                }
            }
        }
    }

    // ───────────────────────── WAV + asset helpers ─────────────────────────

    private float[] loadWav(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        in.skip(40);
        byte[] sizeBytes = new byte[4];
        in.read(sizeBytes);
        int dataSize = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();

        byte[] data = new byte[dataSize];
        int bytesRead = 0;
        while (bytesRead < dataSize) {
            int read = in.read(data, bytesRead, dataSize - bytesRead);
            if (read == -1) break;
            bytesRead += read;
        }
        in.close();

        short[] shorts = new short[dataSize / 2];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
        float[] res = new float[shorts.length];
        for (int i = 0; i < shorts.length; i++) res[i] = shorts[i] / 32768f;
        return res;
    }

    private String copyAssetToCache(String filename) {
        File cacheFile = new File(appContext.getCacheDir(), filename);
        if (!cacheFile.exists()) {
            try (InputStream in = appContext.getAssets().open(filename);
                 FileOutputStream out = new FileOutputStream(cacheFile)) {
                byte[] buf = new byte[4096];
                int read;
                while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            } catch (Exception e) {
                Log.e("SpeakerVerifier", "asset copy failed", e);
                return null;
            }
        }
        return cacheFile.getAbsolutePath();
    }
}