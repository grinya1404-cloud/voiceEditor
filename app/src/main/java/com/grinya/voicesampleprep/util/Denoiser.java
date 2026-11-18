package com.grinya.voicesampleprep.util;

import com.grinya.rnnoisewrapper.RnnoiseWrapper;

public class Denoiser {

  public static float[] denoiseStereo(float[] pcm) {
    if (pcm == null || pcm.length == 0)
      return pcm;

    int frames = pcm.length / 2;
    float[] left = new float[frames];
    float[] right = new float[frames];

    for (int i = 0; i < frames; i++) {
      left[i] = pcm[i * 2];
      right[i] = pcm[i * 2 + 1];
    }

    // G5: один wrapper на оба канала
    RnnoiseWrapper wrapper = null;
    try {
      wrapper = RnnoiseWrapper.create();
      int blockSize = wrapper.frameSize;

      float[] leftDenoised = denoiseMono(wrapper, blockSize, left);
      float[] rightDenoised = denoiseMono(wrapper, blockSize, right);

      float[] out = new float[pcm.length];
      for (int i = 0; i < frames; i++) {
        out[i * 2] = leftDenoised[i];
        out[i * 2 + 1] = rightDenoised[i];
      }
      return out;
    } catch (Throwable e) {
      android.util.Log.e("DENOISER", "Ошибка шумодава", e);
      return pcm;
    } finally {
      if (wrapper != null)
        wrapper.close();
    }
  }

  private static float[] denoiseMono(RnnoiseWrapper wrapper, int blockSize, float[] mono) {
    if (mono.length == 0)
      return mono;

    float[] out = new float[mono.length];
    // G5: переиспользуемые буферы
    float[] inputFrame = new float[blockSize];
    float[] outputFrame = new float[blockSize];

    for (int i = 0; i < mono.length; i += blockSize) {
      int n = Math.min(blockSize, mono.length - i);

      java.util.Arrays.fill(inputFrame, 0f);
      java.util.Arrays.fill(outputFrame, 0f);
      System.arraycopy(mono, i, inputFrame, 0, n);

      wrapper.processFrame(inputFrame, outputFrame);

      System.arraycopy(outputFrame, 0, out, i, n);
    }
    return out;
  }
}