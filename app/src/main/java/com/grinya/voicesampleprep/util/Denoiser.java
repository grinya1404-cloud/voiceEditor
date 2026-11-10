package com.grinya.voicesampleprep.util;

import cn.enaium.rnnoise.Rnnoise;
import cn.enaium.rnnoise.RnnoiseKt;

public class Denoiser {

  private static final int RNNOISE_SAMPLE_RATE = 48000;
  private static final int FRAME_SIZE = 480; // 10 мс при 48 кГц

  /**
   * Шумоподавление стерео PCM.
   *
   * @param pcm      interleaved stereo L,R,L,R...
   * @param sampleRate частота дискретизации исходного pcm
   * @returndenoised pcm с той же частотой и форматом
   */
  public static float[] denoiseStereo(float[] pcm, int sampleRate) {
    if (pcm == null || pcm.length == 0)
      return pcm;

    int frames = pcm.length / 2;

    // RNNoise работает только на 48 кГц — ресемплим если нужно
    float[] workPcm = pcm;
    if (sampleRate != RNNOISE_SAMPLE_RATE) {
      workPcm = AudioOps.resample(pcm, sampleRate, RNNOISE_SAMPLE_RATE);
    }

    int workFrames = workPcm.length / 2;

    // Разделяем на L и R
    float[] left = new float[workFrames];
    float[] right = new float[workFrames];
    for (int i = 0; i < workFrames; i++) {
      left[i] = workPcm[i * 2];
      right[i] = workPcm[i * 2 + 1];
    }

    float[] leftDenoised = denoiseMono(left);
    float[] rightDenoised = denoiseMono(right);

    float[] out = new float[workFrames * 2];
    for (int i = 0; i < workFrames; i++) {
      out[i * 2] = leftDenoised[i];
      out[i * 2 + 1] = rightDenoised[i];
    }

    // Возвращаем в исходную частоту если ресемплили
    if (sampleRate != RNNOISE_SAMPLE_RATE) {
      return AudioOps.resample(out, RNNOISE_SAMPLE_RATE, sampleRate);
    }
    return out;
  }

  private static float[] denoiseMono(float[] mono) {
    if (mono.length == 0)
      return mono;

    Rnnoise rnnoise = null;
    try {
      rnnoise = cn.enaium.rnnoise.RnnoiseKt.createRnnoise(null);
    } catch (Exception e) {
      android.util.Log.e("DENOISER", "createRnnoise failed", e);
      return mono;
    }

    try {
      float[] out = new float[mono.length];

      for (int i = 0; i < mono.length; i += FRAME_SIZE) {
        int n = Math.min(FRAME_SIZE, mono.length - i);
        float[] inputFrame = new float[FRAME_SIZE];
        float[] outputFrame = new float[FRAME_SIZE];
        System.arraycopy(mono, i, inputFrame, 0, n);

        rnnoise.processFrame(inputFrame, outputFrame);

        System.arraycopy(outputFrame, 0, out, i, n);
      }
      return out;
    } catch (Throwable e) {
      android.util.Log.e("DENOISER", "Ошибка шумодава rnnoise-kmp", e);
      return mono;
    } finally {
      if (rnnoise != null) {
        try {
          rnnoise.close();
        } catch (Exception ignored) {
        }
      }
    }
  }
}
