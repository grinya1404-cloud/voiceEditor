package com.grinya.voicesampleprep.util;

public class AudioInfo {
    public float peakDB;       // пик в дБ
    public float rmsDB;        // RMS в дБ
    public boolean clipping;   // есть ли клиппинг (peak > 0.999)
    public float balanceLR;    // разница L-R в дБ (0 = одинаково)
    public int silenceStartMs; // тишина в начале (мс)
    public int silenceEndMs;   // тишина в конце (мс)
    public int totalFrames;
    public int sampleRate;
    public int channels;

    public static AudioInfo analyze(float[] pcm, int sampleRate) {
        AudioInfo info = new AudioInfo();
        info.sampleRate = sampleRate;
        info.channels = 2;
        info.totalFrames = pcm.length / 2;

        if (pcm.length == 0) return info;

        float peakL = 0, peakR = 0;
        double sumL = 0, sumR = 0;
        int count = 0;
        boolean clipping = false;
        
        for (int i = 0; i < pcm.length; i += 2) {
            float l = pcm[i];
            float r = pcm[i + 1];
            float absL = Math.abs(l);
            float absR = Math.abs(r);
            if (absL > peakL) peakL = absL;
            if (absR > peakR) peakR = absR;
            if (absL >= 1f || absR >= 1f) clipping = true;
            sumL += l * l;
            sumR += r * r;
            count++;
        }
        
        float peak = Math.max(peakL, peakR);
        info.peakDB = (float) (20 * Math.log10(Math.max(peak, 1e-9)));
        info.clipping = clipping;
        
        double rmsL = Math.sqrt(sumL / count);
        double rmsR = Math.sqrt(sumR / count);
        double rms = (rmsL + rmsR) / 2;
        info.rmsDB = (float) (20 * Math.log10(Math.max(rms, 1e-9)));
        
        // Баланс L/R в дБ
        float dbL = (float) (20 * Math.log10(Math.max(rmsL, 1e-9)));
        float dbR = (float) (20 * Math.log10(Math.max(rmsR, 1e-9)));
        info.balanceLR = dbL - dbR;
        
        // Тишина в начале/конце (порог -60 дБ = 0.001)
        int startSilence = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            if (Math.abs(pcm[i]) > 0.001f || Math.abs(pcm[i + 1]) > 0.001f) break;
            startSilence++;
        }
        info.silenceStartMs = startSilence * 1000 / sampleRate;
        
        int endSilence = 0;
        for (int i = pcm.length - 2; i >= 0; i -= 2) {
            if (Math.abs(pcm[i]) > 0.001f || Math.abs(pcm[i + 1]) > 0.001f) break;
            endSilence++;
        }
        info.silenceEndMs = endSilence * 1000 / sampleRate;
        
        return info;
    }
} 