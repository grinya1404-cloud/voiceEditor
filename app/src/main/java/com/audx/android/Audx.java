package com.audx.android;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class Audx {
    public static final int FRAME_RATE = 48000;
    public static final int FRAME_SIZE = 480;
    public static final int AUDX_RESAMPLER_QUALITY_MAX = 10;
    public static final int AUDX_RESAMPLER_QUALITY_MIN = 0;
    public static final int AUDX_RESAMPLER_QUALITY_DEFAULT = 4;
    public static final int AUDX_RESAMPLER_QUALITY_VOIP = 3;

    private static final int SKIP_FIRST_N_FRAMES = 1;

    private int inputRate;
    private int resampleQuality;
    private long denoisePtr = 0;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private long frameCount = 0;

    static {
        System.loadLibrary("audx-android");
    }

    public static class Builder {
        private int inputRate = FRAME_RATE;
        private int resampleQuality = AUDX_RESAMPLER_QUALITY_DEFAULT;

        public Builder inputRate(int rate) {
            this.inputRate = rate;
            return this;
        }

        public Builder resampleQuality(int quality) {
            this.resampleQuality = quality;
            return this;
        }

        public Audx build() {
            Audx audx = new Audx(inputRate, resampleQuality);
            audx.create();
            return audx;
        }
    }

    private Audx(int inputRate, int resampleQuality) {
        this.inputRate = inputRate;
        this.resampleQuality = resampleQuality;
    }

    public void create() {
        long ptr = denoiseCreateJNI(inputRate, resampleQuality);
        if (ptr == -1L) {
            throw new RuntimeException("Failed to initialize Audx");
        }
        denoisePtr = ptr;
        frameCount = 0;
    }

    public void process(short[] input, short[] output, VadCallback callback) {
        checkNotClosed("process");
        if (denoisePtr == 0) throw new IllegalStateException("Native pointer is null");
        
        float result = denoiseProcessJNI(denoisePtr, input, output);
        
        frameCount++;
        if (frameCount <= SKIP_FIRST_N_FRAMES) {
            Arrays.fill(output, (short) 0);
        }
        
        if (callback != null) {
            callback.onVad(result);
        }
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (denoisePtr != 0) {
                denoiseDestroyJNI(denoisePtr);
                denoisePtr = 0;
            }
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    private void checkNotClosed(String methodName) {
        if (closed.get()) {
            throw new IllegalStateException("Cannot call " + methodName + "() on closed Audx instance");
        }
    }

    public interface VadCallback {
        void onVad(float probability);
    }

    // Нативные методы (JNI)
    private native long denoiseCreateJNI(int inRate, int resampleQuality);
    private native float denoiseProcessJNI(long ptr, short[] input, short[] output);
    private native void denoiseDestroyJNI(long ptr);
}