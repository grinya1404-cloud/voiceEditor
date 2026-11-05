package com.grinya.voicesampleprep.util;

public final class AudioOps {

    private AudioOps() {}

    /** Обрезка по индексам фреймов [startFrame, endFrame). */
    public static float[] trim(float[] pcm, int startFrame, int endFrame) {
        int ch = 2;
        int from = Math.max(0, startFrame) * ch;
        int to = Math.min(pcm.length / ch, endFrame) * ch;
        if (to <= from) return new float[0];
        float[] out = new float[to - from];
        System.arraycopy(pcm, from, out, 0, out.length);
        return out;
    }

    /** Удалить фрагмент [startFrame, endFrame), сдвинув остаток. */
    public static float[] deleteRange(float[] pcm, int startFrame, int endFrame) {
        int ch = 2;
        int totalFrames = pcm.length / ch;
        int s = Math.max(0, Math.min(startFrame, totalFrames));
        int e = Math.max(s, Math.min(endFrame, totalFrames));
        int removed = (e - s) * ch;
        float[] out = new float[pcm.length - removed];
        System.arraycopy(pcm, 0, out, 0, s * ch);
        System.arraycopy(pcm, e * ch, out, s * ch, pcm.length - e * ch);
        return out;
    }

    /** Заменить фрагмент тишиной. */
    public static float[] silenceRange(float[] pcm, int startFrame, int endFrame) {
        int ch = 2;
        int totalFrames = pcm.length / ch;
        int s = Math.max(0, Math.min(startFrame, totalFrames));
        int e = Math.max(s, Math.min(endFrame, totalFrames));
        float[] out = pcm.clone();
        for (int i = s * ch; i < e * ch; i++) out[i] = 0f;
        return out;
    }

    /** Усиление на dB (может быть отрицательным). */
    public static float[] gain(float[] pcm, float dB) {
        float mul = (float) Math.pow(10.0, dB / 20.0);
        float[] out = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) out[i] = pcm[i] * mul;
        return out;
    }

    /** Усиление только к диапазону фреймов. */
    public static float[] gainRange(float[] pcm, int startFrame, int endFrame, float dB) {
        int ch = 2;
        int totalFrames = pcm.length / ch;
        int s = Math.max(0, Math.min(startFrame, totalFrames));
        int e = Math.max(s, Math.min(endFrame, totalFrames));
        float mul = (float) Math.pow(10.0, dB / 20.0);
        float[] out = pcm.clone();
        for (int i = s * ch; i < e * ch; i++) out[i] *= mul;
        return out;
    }

    /** Пиковая нормализация к target (0..1). */
    public static float[] normalizePeak(float[] pcm, float target) {
        float peak = 0f;
        for (float v : pcm) {
            float a = Math.abs(v);
            if (a > peak) peak = a;
        }
        if (peak <= 1e-9f) return pcm.clone();
        float mul = target / peak;
        float[] out = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) out[i] = pcm[i] * mul;
        return out;
    }

    /** Fade-in (линейный) длиной fadeFrames. */
    public static float[] fadeIn(float[] pcm, int fadeFrames) {
        int ch = 2;
        float[] out = pcm.clone();
        int totalFrames = out.length / ch;
        int n = Math.min(fadeFrames, totalFrames);
        for (int i = 0; i < n; i++) {
            float g = (float) i / n;
            out[i * ch] *= g;
            out[i * ch + 1] *= g;
        }
        return out;
    }

    /** Fade-out (линейный) длиной fadeFrames. */
    public static float[] fadeOut(float[] pcm, int fadeFrames) {
        int ch = 2;
        float[] out = pcm.clone();
        int totalFrames = out.length / ch;
        int n = Math.min(fadeFrames, totalFrames);
        for (int i = 0; i < n; i++) {
            float g = 1f - (float) i / n;
            int idx = (totalFrames - n + i) * ch;
            out[idx] *= g;
            out[idx + 1] *= g;
        }
        return out;
    }

    /** Реверс (по фреймам, каналы остаются на своих местах). */
    public static float[] reverse(float[] pcm) {
        int ch = 2;
        int n = pcm.length / ch;
        float[] out = new float[pcm.length];
        for (int i = 0; i < n; i++) {
            out[i * ch]     = pcm[(n - 1 - i) * ch];
            out[i * ch + 1] = pcm[(n - 1 - i) * ch + 1];
        }
        return out;
    }

    /** Свести в моно (пересчитывает массив: каждый фрейм — один сэмпл). */
    public static float[] toMono(float[] pcm) {
        int frames = pcm.length / 2;
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            out[i] = (pcm[i * 2] + pcm[i * 2 + 1]) * 0.5f;
        }
        return out;
    }

    /** Линейный ресемплинг interleaved stereo. */
    public static float[] resample(float[] pcm, int srcRate, int dstRate) {
        if (srcRate == dstRate) return pcm.clone();
        int ch = 2;
        int srcFrames = pcm.length / ch;
        if (srcFrames == 0) return new float[0];
        int dstFrames = (int) Math.round((double) srcFrames * dstRate / srcRate);
        float[] out = new float[dstFrames * ch];
        double ratio = (double) srcRate / dstRate;
        for (int i = 0; i < dstFrames; i++) {
            double srcPos = i * ratio;
            int i0 = (int) srcPos;
            int i1 = Math.min(i0 + 1, srcFrames - 1);
            float frac = (float) (srcPos - i0);
            for (int c = 0; c < ch; c++) {
                float a = pcm[i0 * ch + c];
                float b = pcm[i1 * ch + c];
                out[i * ch + c] = a + (b - a) * frac;
            }
        }
        return out;
    }

    /** Длина в фреймах. */
    public static int frameCount(float[] pcm) {
        return pcm.length / 2;
    }

    /** Найти ближайший zero-crossing в стерео (по сумме каналов) к targetFrame. */
    public static int nearestZeroCrossing(float[] pcm, int targetFrame) {
        int frames = frameCount(pcm);
        if (frames == 0) return 0;
        targetFrame = Math.max(0, Math.min(frames - 1, targetFrame));
        int search = Math.min(2048, frames / 2);
        int best = targetFrame;
        int bestDist = Integer.MAX_VALUE;
        for (int d = 0; d <= search; d++) {
            int a = targetFrame - d;
            int b = targetFrame + d;
            if (a >= 0 && isZeroCross(pcm, a)) {
                if (d < bestDist) { bestDist = d; best = a; }
            }
            if (b < frames && isZeroCross(pcm, b)) {
                if (d < bestDist) { bestDist = d; best = b; }
            }
            if (bestDist <= 1) break;
        }
        return best;
    }

    private static boolean isZeroCross(float[] pcm, int frame) {
        if (frame <= 0) return true;
        float prev = pcm[(frame - 1) * 2] + pcm[(frame - 1) * 2 + 1];
        float cur  = pcm[frame * 2] + pcm[frame * 2 + 1];
        return (prev <= 0f && cur >= 0f) || (prev >= 0f && cur <= 0f);
    }
}