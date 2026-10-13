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
import java.util.Set;

public class SpeakerVerifier {
    private static SpeakerVerifier instance;
    private final Context appContext;
    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;

    private float[][] poiEmbeddings;
    private float[][] poniEmbeddings;
    private String currentFolderUri;

    private static final String MODEL_NAME = "redimnet2_b0_lm.onnx";
    private static final int EMB_DIM = 192;
    private static final int MIN_SAMPLES = 16000;       // 1 s
    private static final int MAX_SAMPLES = 16000 * 30;  // 30 s

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
            opts.addNnapi(); // Uncomment for NNAPI acceleration if supported on your target devices
            String modelPath = copyAssetToCache(MODEL_NAME);
            if (modelPath != null) {
                session = env.createSession(modelPath, opts);
                Set<String> names = session.getInputNames();
                inputName = names.iterator().next();
                DebugLogger.log("SpeakerVerifier: initialized, inputName=" + inputName);
            }
        } catch (Exception e) {
            DebugLogger.log("SpeakerVerifier: Init failed " + e.getMessage());
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
            DebugLogger.log("SpeakerVerifier: NPY read error " + e.getMessage());
            Log.e("SpeakerVerifier", "NPY read error", e);
            return new float[0][];
        }
    }

    // ───────────────────────── Verification ─────────────────────────

    public boolean verify(File wavFile) {
        DebugLogger.log("SpeakerVerifier.verify: file=" + wavFile.getName() + ", size=" + wavFile.length() + " bytes");
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
            DebugLogger.log("SpeakerVerifier.verify: loaded " + wav.length + " samples (" + (wav.length / 16000f) + "s)");

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

    // ───────────────────────── Embedding extraction (Raw Waveform) ─────────────────────────

    private float[] extractEmbedding(float[] wav) throws Exception {
        // Match python script: pad to 1s, truncate to 30s
        if (wav.length < MIN_SAMPLES) {
            float[] padded = new float[MIN_SAMPLES];
            System.arraycopy(wav, 0, padded, 0, wav.length);
            wav = padded;
        } else if (wav.length > MAX_SAMPLES) {
            wav = Arrays.copyOf(wav, MAX_SAMPLES);
        }
        
        OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(wav), new long[]{1, wav.length});
        OrtSession.Result result = null;
        try {
            result = session.run(Collections.singletonMap(inputName, tensor));
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
                DebugLogger.log("SpeakerVerifier: asset copy failed " + e.getMessage());
                Log.e("SpeakerVerifier", "asset copy failed", e);
                return null;
            }
        }
        return cacheFile.getAbsolutePath();
    }
}