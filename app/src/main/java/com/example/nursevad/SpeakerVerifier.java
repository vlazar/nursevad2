package com.example.nursevad;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

public class SpeakerVerifier {
    private static final String TAG = "SpeakerVerifier";
    private static final String MODEL_NAME = "redimnet2_b0_lm.onnx";
    
    private final Context context;
    private OrtEnvironment ortEnv;
    private OrtSession session;
    private Map<String, float[]> enrolledEmbeddings;

    public SpeakerVerifier(Context context) {
        this.context = context;
        this.ortEnv = OrtEnvironment.getEnvironment();
        initSession();
    }

    private void initSession() {
        try {
            String modelPath = copyAssetToCache(MODEL_NAME);
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.addNnapi(); // Uncomment for NNAPI acceleration if supported
            session = ortEnv.createSession(modelPath, options);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ONNX session", e);
        }
    }

    private String copyAssetToCache(String filename) throws Exception {
        File cacheFile = new File(context.getCacheDir(), filename);
        if (!cacheFile.exists()) {
            try (InputStream in = context.getAssets().open(filename);
                 FileOutputStream out = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        }
        return cacheFile.getAbsolutePath();
    }

    public void loadEmbeddings(File folder) {
        // Implementation stays the same as your current logic
        // e.g. reading .txt or .npy files mapping user ID to 192-d float array
        enrolledEmbeddings = new HashMap<>();
        // ... your existing loading logic ...
    }

    public boolean verify(float[] audio16k, boolean isPoi) {
        if (session == null || enrolledEmbeddings == null || enrolledEmbeddings.isEmpty()) {
            return false;
        }

        float[] currentEmbedding = extractEmbedding(audio16k);
        if (currentEmbedding == null) return false;

        // POI/PONI handling logic stays the same
        float maxSim = -1f;
        String bestMatch = null;
        
        for (Map.Entry<String, float[]> entry : enrolledEmbeddings.entrySet()) {
            float sim = cosineSimilarity(currentEmbedding, entry.getValue());
            if (sim > maxSim) {
                maxSim = sim;
                bestMatch = entry.getKey();
            }
        }

        // ... your existing POI/PONI threshold logic ...
        return false; // placeholder
    }

    private float cosineSimilarity(float[] a, float[] b) {
        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    private float[] extractEmbedding(float[] audio16k) {
        try {
            float[] features = extractReDimNet2Features(audio16k);
            int numFrames = features.length / 72;
            
            OnnxTensor tensor = null;
            OrtSession.Result result = null;
            try {
                tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(features), new long[]{1, 1, 72, numFrames});
                result = session.run(Collections.singletonMap("features", tensor));
                
                float[][] output = (float[][]) result.get("embedding").get().getValue();
                float[] emb = output[0];
                
                // Ensure L2 normalized
                float norm = 0;
                for (float v : emb) norm += v * v;
                norm = (float) Math.sqrt(norm);
                if (norm > 0) {
                    for (int i = 0; i < emb.length; i++) emb[i] /= norm;
                }
                return emb;
            } finally {
                if (tensor != null) tensor.close();
                if (result != null) result.close();
            }
        } catch (OrtException e) {
            Log.e(TAG, "ONNX inference failed", e);
            return null;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Audio preprocessing failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Exact replication of the ReDimNet2 ONNX frontend:
     * Mean/Std norm -> Pre-emphasis -> STFT -> Mel -> Log -> Time-Mean Norm
     */
    private float[] extractReDimNet2Features(float[] audio) {
        if (audio.length < 16000) throw new IllegalArgumentException("Audio too short (<1s)");
        
        // 1. Mean/Std normalization
        float mean = 0;
        for (float v : audio) mean += v;
        mean /= audio.length;
        float var = 0;
        for (float v : audio) var += (v - mean) * (v - mean);
        float std = (float) Math.sqrt(var / audio.length) + 1e-8f;
        float[] norm = new float[audio.length];
        for (int i = 0; i < audio.length; i++) norm[i] = (audio[i] - mean) / std;

        // 2. Pre-emphasis with reflect padding
        float[] pre = new float[norm.length];
        for (int i = 0; i < norm.length; i++) {
            float prev = (i == 0) ? norm[1] : norm[i - 1];
            pre[i] = norm[i] - 0.97f * prev;
        }

        // 3. STFT
        int fftSize = 512;
        int frameLength = 400;
        int hopLength = 160;
        int pad = 80; // FRAME_STEP // 2
        
        float[] padded = new float[pre.length + 2 * pad];
        System.arraycopy(pre, 0, padded, pad, pre.length);
        
        int numFrames = 0;
        for (int i = 0; i + frameLength <= padded.length; i += hopLength) numFrames++;
        
        // Time alignment: must be divisible by 4
        numFrames = (numFrames / 4) * 4;
        if (numFrames < 4) throw new IllegalArgumentException("Audio too short for ReDimNet2");

        float[] window = new float[frameLength];
        for (int i = 0; i < frameLength; i++) {
            window[i] = 0.54f - 0.46f * (float) Math.cos(2 * Math.PI * i / (frameLength - 1));
        }

        float[][] powerSpec = new float[numFrames][256];
        float[] real = new float[fftSize];
        float[] imag = new float[fftSize];

        for (int f = 0; f < numFrames; f++) {
            int start = f * hopLength;
            Arrays.fill(real, 0);
            Arrays.fill(imag, 0);
            for (int i = 0; i < frameLength; i++) real[i] = padded[start + i] * window[i];
            
            fft(real, imag);
            for (int k = 0; k < 256; k++) {
                float p = real[k] * real[k] + imag[k] * imag[k];
                powerSpec[f][k] = Math.max(p, 1e-8f);
            }
        }

        // 4. Mel Filterbank (72 bins, 20-7600Hz)
        int numMels = 72;
        float[][] mel = new float[numFrames][numMels];
        float mel20 = hzToMel(20);
        float mel7600 = hzToMel(7600);
        float[] points = new float[74];
        for (int i = 0; i < 74; i++) points[i] = mel20 + i * (mel7600 - mel20) / 73f;

        for (int f = 0; f < numFrames; f++) {
            for (int m = 0; m < numMels; m++) {
                float sum = 0;
                for (int k = 1; k < 256; k++) { // k=0 is 0
                    float fftFreq = k * (8000f / 256f);
                    float binMel = hzToMel(fftFreq);
                    float lower = points[m];
                    float center = points[m+1];
                    float upper = points[m+2];
                    float val1 = (binMel - lower) / (center - lower);
                    float val2 = (upper - binMel) / (upper - center);
                    float weight = Math.max(0f, Math.min(val1, val2));
                    sum += powerSpec[f][k] * weight;
                }
                mel[f][m] = Math.max(sum, 1e-8f);
            }
        }

        // 5. Log and Mean Normalization (over time)
        for (int f = 0; f < numFrames; f++) {
            for (int m = 0; m < numMels; m++) mel[f][m] = (float) Math.log(mel[f][m] + 1e-8f);
        }
        for (int m = 0; m < numMels; m++) {
            float meanMel = 0;
            for (int f = 0; f < numFrames; f++) meanMel += mel[f][m];
            meanMel /= numFrames;
            for (int f = 0; f < numFrames; f++) mel[f][m] -= meanMel;
        }

        // 6. Flatten to [1, 1, 72, T] (C-contiguous layout)
        float[] flat = new float[numMels * numFrames];
        for (int m = 0; m < numMels; m++) {
            for (int f = 0; f < numFrames; f++) {
                flat[m * numFrames + f] = mel[f][m];
            }
        }
        return flat;
    }

    private float hzToMel(float hz) {
        return 2595f * (float) Math.log10(1 + hz / 700f);
    }

    /**
     * Minimal in-place Radix-2 Cooley-Tukey FFT for power-of-2 sizes.
     */
    private void fft(float[] real, float[] imag) {
        int n = real.length;
        for (int i = 0; i < n; i++) {
            int j = Integer.reverse(i) >>> (32 - Integer.numberOfTrailingZeros(n));
            if (j > i) {
                float temp = real[i]; real[i] = real[j]; real[j] = temp;
                temp = imag[i]; imag[i] = imag[j]; imag[j] = temp;
            }
        }
        for (int size = 2; size <= n; size *= 2) {
            int half = size / 2;
            double angle = -2 * Math.PI / size;
            float wReal = (float) Math.cos(angle);
            float wImag = (float) Math.sin(angle);
            for (int i = 0; i < n; i += size) {
                float curReal = 1, curImag = 0;
                for (int j = 0; j < half; j++) {
                    float tReal = curReal * real[i + j + half] - curImag * imag[i + j + half];
                    float tImag = curReal * imag[i + j + half] + curImag * real[i + j + half];
                    real[i + j + half] = real[i + j] - tReal;
                    imag[i + j + half] = imag[i + j] - tImag;
                    real[i + j] += tReal;
                    imag[i + j] += tImag;
                    float newCurReal = curReal * wReal - curImag * wImag;
                    curImag = curReal * wImag + curImag * wReal;
                    curReal = newCurReal;
                }
            }
        }
    }
}