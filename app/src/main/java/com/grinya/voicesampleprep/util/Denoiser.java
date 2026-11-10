package com.grinya.voicesampleprep.util;

import com.grinya.rnnoisewrapper.RnnoiseWrapper;

public class Denoiser {

  public static float[] denoiseStereo(float[] pcm) {
    if (pcm == null || pcm.length == 0)
      return pcm;

    int frames = pcm.length / 2;
    float[] left = new float[frames];
    float[] right = new float[frames];

    // Разделяем стерео на два моно-канала
    for (int i = 0; i < frames; i++) {
      left[i] = pcm[i * 2];
      right[i] = pcm[i * 2 + 1];
    }

    // Обрабатываем каждый канал
    float[] leftDenoised = denoiseMono(left);
    float[] rightDenoised = denoiseMono(right);

    // Собираем обратно в стерео
    float[] out = new float[pcm.length];
    for (int i = 0; i < frames; i++) {
      out[i * 2] = leftDenoised[i];
      out[i * 2 + 1] = rightDenoised[i];
    }
    return out;
  }

  private static float[] denoiseMono(float[] mono) {
    if (mono.length == 0)
      return mono;

    RnnoiseWrapper wrapper = null;
    try {
      // Создаем шумодав (используется встроенная модель)
      wrapper = RnnoiseWrapper.create();
      int blockSize = wrapper.frameSize;

      float[] out = new float[mono.length];

      for (int i = 0; i < mono.length; i += blockSize) {
        int n = Math.min(blockSize, mono.length - i);
        float[] inputFrame = new float[blockSize];
        float[] outputFrame = new float[blockSize];

        // Копируем кусок (в хвосте будут нули, это нормально)
        System.arraycopy(mono, i, inputFrame, 0, n);

        // Обрабатываем кадр
        wrapper.processFrame(inputFrame, outputFrame);

        // Забираем результат
        System.arraycopy(outputFrame, 0, out, i, n);
      }
      return out;
    } catch (Throwable e) {
      android.util.Log.e("DENOISER", "Ошибка шумодава", e);
      return mono; // Возвращаем оригинал, если что-то пошло не так
    } finally {
      if (wrapper != null) {
        wrapper.close();
      }
    }
  }
}