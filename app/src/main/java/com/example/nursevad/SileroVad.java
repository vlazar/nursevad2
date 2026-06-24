package com.example.nursevad;

import android.content.Context;
import ai.onnxruntime.*;
import java.io.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SileroVad {
    private OrtEnvironment env;
    private OrtSession session;
    
    // Model state and context for streaming inference
    private float[][][] state;
    private float[][] context;
    private int lastSr = 0;
    private int lastBatchSize = 0;
    
    private static final List<Integer> SAMPLE_RATES = Arrays.asList(8000, 16000);

    public SileroVad(Context context) {
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setInterOpNumThreads(1);
            opts.setIntraOpNumThreads(1);
            opts.addCPU(true);
            
            String modelPath = copyAssetToCache(context, "silero_vad.onnx");
            if (modelPath == null) {
                EventBus.getInstance().postStatus("ERR: Model file missing or corrupt.");
                return;
            }
            session = env.createSession(modelPath, opts);
            resetStates();
        } catch (Throwable e) { 
            EventBus.getInstance().postStatus("ERR: ONNX Init failed: " + e.getMessage());
        }
    }

    void resetStates() {
        state = new float[2][1][128];
        context = new float[0][];
        lastSr = 0;
        lastBatchSize = 0;
    }

    public float predict(short[] audioChunk) {
        if (session == null) return 0; 
        
        int sr = 16000;
        // Convert short[] to float[][] for ONNX
        float[][] x = new float[1][audioChunk.length];
        for (int i = 0; i < audioChunk.length; i++) {
            x[0][i] = audioChunk[i] / 32768.0f;
        }

        try {
            int batchSize = x.length;
            int numSamples = sr == 16000 ? 512 : 256;
            int contextSize = sr == 16000 ? 64 : 32;

            // Reset states if sample rate or batch size changes
            if (lastSr != 0 && lastSr != sr) {
                resetStates();
            } else if (lastBatchSize != 0 && lastBatchSize != batchSize) {
                resetStates();
            } else if (lastBatchSize == 0) {
                lastBatchSize = batchSize;
            }

            // Initialize context if empty
            if (context.length == 0) {
                context = new float[batchSize][contextSize];
            }

            // CRITICAL: Prepend context (64 samples) to current chunk (512 samples)
            float[][] xWithContext = new float[batchSize][contextSize + numSamples];
            for (int i = 0; i < batchSize; i++) {
                System.arraycopy(context[i], 0, xWithContext[i], 0, contextSize);
                System.arraycopy(x[i], 0, xWithContext[i], contextSize, numSamples);
            }

            OnnxTensor inputTensor = null;
            OnnxTensor stateTensor = null;
            OnnxTensor srTensor = null;
            OrtSession.Result ortOutputs = null;

            try {
                // Create tensors directly from multi-dimensional arrays
                inputTensor = OnnxTensor.createTensor(env, xWithContext);
                stateTensor = OnnxTensor.createTensor(env, state);
                srTensor = OnnxTensor.createTensor(env, new long[]{sr});

                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("input", inputTensor);
                inputs.put("sr", srTensor);
                inputs.put("state", stateTensor);

                ortOutputs = session.run(inputs);
                
                // Extract outputs
                float[][] output = (float[][]) ortOutputs.get(0).getValue();
                state = (float[][][]) ortOutputs.get(1).getValue();

                // Update context for the next frame (save the last 64 samples)
                for (int i = 0; i < batchSize; i++) {
                    System.arraycopy(xWithContext[i], xWithContext[i].length - contextSize,
                            context[i], 0, contextSize);
                }

                lastSr = sr;
                lastBatchSize = batchSize;
                
                // Return the speech probability
                return output[0][0];

            } finally {
                if (inputTensor != null) inputTensor.close();
                if (stateTensor != null) stateTensor.close();
                if (srTensor != null) srTensor.close();
                if (ortOutputs != null) ortOutputs.close();
            }
        } catch (Throwable e) {
            EventBus.getInstance().postStatus("ERR: VAD Predict failed: " + e.getMessage());
            return 0;
        }
    }

    private String copyAssetToCache(Context context, String filename) {
        File cacheFile = new File(context.getCacheDir(), filename);
        if (!cacheFile.exists()) {
            try (InputStream in = context.getAssets().open(filename);
                 OutputStream out = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[4096];
                int read;
                long totalBytes = 0;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    totalBytes += read;
                }
                if (totalBytes < 100000) {
                    EventBus.getInstance().postStatus("ERR: Model file is too small. Download the RAW .onnx file!");
                    return null;
                }
            } catch (IOException e) { 
                EventBus.getInstance().postStatus("ERR: Asset copy failed: " + e.getMessage());
                return null;
            }
        }
        return cacheFile.getAbsolutePath();
    }
}