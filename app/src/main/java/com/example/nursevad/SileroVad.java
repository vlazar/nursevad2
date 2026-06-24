package com.example.nursevad;

import android.content.Context;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

public class SileroVad {
    private OrtEnvironment env;
    private OrtSession session;
    private float[] state = new float[2 * 1 * 128];
    private long sr = 16000;
    private int chunkSize = 512; 

    public SileroVad(Context context) {
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(1); // Prevents threading crashes on mobile ARM chips
            String modelPath = copyAssetToCache(context, "silero_vad.onnx");
            session = env.createSession(modelPath, opts);
        } catch (OrtException e) { 
            EventBus.getInstance().postStatus("ERR: ONNX Init failed: " + e.getMessage());
        }
    }

    public float predict(short[] audioChunk) {
        if (session == null) return 0; // Prevent crash if ONNX failed to load
        try {
            float[] input = new float[chunkSize];
            for(int i=0; i<chunkSize; i++) input[i] = audioChunk[i] / 32768.0f;
            
            FloatBuffer inputBuffer = FloatBuffer.wrap(input);
            FloatBuffer stateBuffer = FloatBuffer.wrap(state);
            LongBuffer srBuffer = LongBuffer.wrap(new long[]{sr});

            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputBuffer, new long[]{1, chunkSize});
            OnnxTensor stateTensor = OnnxTensor.createTensor(env, stateBuffer, new long[]{2, 1, 128});
            OnnxTensor srTensor = OnnxTensor.createTensor(env, srBuffer, new long[]{1});

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input", inputTensor);
            inputs.put("state", stateTensor);
            inputs.put("sr", srTensor);

            OrtSession.Result result = session.run(inputs);
            float prob = ((float[][]) result.get("output").get().getValue())[0][0];
            float[] newState = ((float[][][]) result.get("stateN").get().getValue())[0][0];
            System.arraycopy(newState, 0, state, 0, state.length);

            inputTensor.close(); stateTensor.close(); srTensor.close(); result.close();
            return prob;
        } catch (OrtException e) { return 0; }
    }

    private String copyAssetToCache(Context context, String filename) {
        File cacheFile = new File(context.getCacheDir(), filename);
        if (!cacheFile.exists()) {
            try (InputStream in = context.getAssets().open(filename);
                 OutputStream out = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            } catch (IOException e) { e.printStackTrace(); }
        }
        return cacheFile.getAbsolutePath();
    }
}