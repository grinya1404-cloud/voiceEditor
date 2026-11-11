package com.grinya.voicesampleprep.model;

import com.grinya.voicesampleprep.edit.UndoStack;
import com.grinya.voicesampleprep.util.AudioOps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AudioClip {

  public static final int CHANNELS = 2;
  public static final int SAMPLE_RATE = 48000;
  public java.io.File dir;

  public String name;
  public float[] pcm; // interleaved stereo L,R,L,R...
  public int sampleRate;
  public int channels;
  public List<Integer> markers = new ArrayList<>(); // индексы фреймов

  private final UndoStack history = new UndoStack();

  public AudioClip(String name, float[] pcm, int sampleRate) {
    this.name = name;
    this.pcm = pcm;
    this.sampleRate = sampleRate;
    this.channels = CHANNELS;
  }

  public int frameCount() {
    return pcm.length / CHANNELS;
  }

  public double durationSec() {
    return frameCount() / (double) sampleRate;
  }

  // ---------- операции ----------

  public void trim(int startFrame, int endFrame) {
    pushHistory();
    pcm = AudioOps.trim(pcm, startFrame, endFrame);
    fixMarkersAfterTrim(startFrame, endFrame);
  }

  /**
   * Автоматически обрезает тишину в начале и конце.
   * 
   * @param threshold Порог амплитуды (0..1). Например, 0.003f (это ~ -50 dB).
   */
  /**
   * Автоматически обрезает тишину.
   * 
   * @param startFrame начальный фрейм (0, если весь файл)
   * @param endFrame   конечный фрейм (frameCount(), если весь файл)
   * @param threshold  порог амплитуды (0..1)
   * @param applyL     учитывать левый канал
   * @param applyR     учитывать правый канал
   */
  public void autoTrim(int startFrame, int endFrame, float threshold, boolean applyL,
      boolean applyR) {
    if (pcm == null || pcm.length == 0)
      return;

    int total = frameCount();
    int s = Math.max(0, Math.min(startFrame, total));
    int e = Math.max(s, Math.min(endFrame, total));
    if (e <= s)
      return;

    // Вырезаем кусок
    float[] range = AudioOps.trim(pcm, s, e);

    // Вычисляем каналы (1 или 2)
    int ch = 2;
    // (Если хочешь, чтобы для моно был 1 канал, добавь проверку, но у нас всегда стерео)

    // Применяем trimSilence
    float[] trimmed = AudioOps.trimSilence(range, threshold, ch);

    // Если ничего не обрезалось (тишина не найдена), выходим
    if (trimmed.length == 0)
      return;
    if (trimmed.length == range.length)
      return;

    // Вставляем обратно (заменяем выделенный кусок на обрезанный)
    float[] newPcm = new float[pcm.length - range.length + trimmed.length];
    System.arraycopy(pcm, 0, newPcm, 0, s * 2);
    System.arraycopy(trimmed, 0, newPcm, s * 2, trimmed.length);
    System.arraycopy(pcm, e * 2, newPcm, (s * 2) + trimmed.length, (total - e) * 2);

    pushHistory();
    pcm = newPcm;

    // Корректируем маркеры (удаляем те, что были внутри вырезанного)
    List<Integer> newMarkers = new ArrayList<>();
    for (int m : markers) {
      if (m < s)
        newMarkers.add(m);
      else if (m >= e)
        newMarkers.add(m - (range.length - trimmed.length) / 2);
    }
    markers = newMarkers;
  }

  public void delete(int startFrame, int endFrame) {
    pushHistory();
    pcm = AudioOps.deleteRange(pcm, startFrame, endFrame);
    fixMarkersAfterDelete(startFrame, endFrame);
  }

  public void gain(float dB) {
    pushHistory();
    pcm = AudioOps.gain(pcm, dB);
  }

  public void silence(int startFrame, int endFrame, boolean applyL, boolean applyR) {
    pushHistory();
    pcm = AudioOps.silenceRange(pcm, startFrame, endFrame, applyL, applyR);
  }

  public void gainRange(int startFrame, int endFrame, float dB, boolean applyL, boolean applyR) {
    pushHistory();
    pcm = AudioOps.gainRange(pcm, startFrame, endFrame, dB, applyL, applyR);
  }

  public void normalizeRangePeak(int startFrame, int endFrame, float target, boolean applyL,
      boolean applyR) {
    pushHistory();
    pcm = AudioOps.normalizeRangePeak(pcm, startFrame, endFrame, target, applyL, applyR);
  }

  public void normalizePeak(float target, boolean applyL, boolean applyR) {
    pushHistory();
    // Если оба канала включены — используем старый быстрый метод (нормализация всего файла)
    if (applyL && applyR) {
      pcm = AudioOps.normalizePeak(pcm, target);
    } else {
      // Если выбран только один канал — используем диапазонный метод на весь файл
      pcm = AudioOps.normalizeRangePeak(pcm, 0, frameCount(), target, applyL, applyR);
    }
  }

  public void fadeIn(int fadeFrames, boolean applyL, boolean applyR) {
    pushHistory();
    pcm = AudioOps.fadeIn(pcm, fadeFrames, applyL, applyR);
  }

  public void fadeOut(int fadeFrames, boolean applyL, boolean applyR) {
    pushHistory();
    pcm = AudioOps.fadeOut(pcm, fadeFrames, applyL, applyR);
  }

  public void reverse(boolean applyL, boolean applyR) {
    pushHistory();
    pcm = AudioOps.reverse(pcm, applyL, applyR);
    int total = frameCount();
    List<Integer> newMarkers = new ArrayList<>();
    for (int m : markers)
      newMarkers.add(total - 1 - m);
    Collections.sort(newMarkers);
    markers = newMarkers;
  }

  public void reverseRange(int startFrame, int endFrame, boolean applyL, boolean applyR) {
    pushHistory();
    pcm = AudioOps.reverseRange(pcm, startFrame, endFrame, applyL, applyR);

    // Пересчитываем маркеры (только те, что внутри диапазона)
    int s = Math.max(0, Math.min(startFrame, frameCount()));
    int e = Math.max(s, Math.min(endFrame, frameCount()));
    List<Integer> newMarkers = new ArrayList<>();
    for (int m : markers) {
      if (m >= s && m < e) {
        // зеркалим внутри диапазона
        newMarkers.add(s + (e - 1 - m));
      } else {
        newMarkers.add(m);
      }
    }
    Collections.sort(newMarkers);
    markers = newMarkers;
  }

  public void invertPhaseRange(int startFrame, int endFrame, boolean applyL, boolean applyR) {
    pushHistory();
    pcm = AudioOps.invertPhase(pcm, startFrame, endFrame, applyL, applyR);
  }

  /**
   * Инвертирует фазу. Если выделения нет (selStart == -1), инвертирует весь файл.
   */
  public void invertPhase(int startFrame, int endFrame, boolean applyL, boolean applyR) {
    pushHistory();
    pcm = AudioOps.invertPhase(pcm, startFrame, endFrame, applyL, applyR);
  }

  public void setPcm(float[] newPcm) {
    pushHistory();
    pcm = newPcm;
  }

  // ---------- undo/redo ----------

  private void pushHistory() {
    history.push(pcm.clone(), markers);
  }

  public boolean canUndo() {
    return history.canUndo();
  }

  public boolean canRedo() {
    return history.canRedo();
  }

  public void undo() {
    UndoStack.Snapshot s = history.undo(pcm, markers);
    if (s != null) {
      pcm = s.pcm;
      markers = s.markers;
    }
  }

  public void redo() {
    UndoStack.Snapshot s = history.redo(pcm, markers);
    if (s != null) {
      pcm = s.pcm;
      markers = s.markers;
    }
  }

  // ---------- маркеры ----------

  public void addMarker(int frame) {
    frame = Math.max(0, Math.min(frameCount() - 1, frame));
    if (!markers.contains(frame)) {
      markers.add(frame);
      Collections.sort(markers);
    }
  }

  public void removeMarker(int frame) {
    markers.remove(Integer.valueOf(frame));
  }

  public void clearMarkers() {
    markers.clear();
  }

  // ---------- коррекция маркеров ----------

  private void fixMarkersAfterTrim(int startFrame, int endFrame) {
    int span = endFrame - startFrame;
    List<Integer> out = new ArrayList<>();
    for (int m : markers) {
      if (m >= startFrame && m < endFrame)
        out.add(m - startFrame);
    }
    // всё, что левее — к 0, правее — к концу
    Collections.sort(out);
    markers = out;
    // защита от выхода за пределы (не должно случиться, но на всякий случай)
    int max = Math.max(0, span - 1);
    for (int i = 0; i < markers.size(); i++) {
      if (markers.get(i) > max)
        markers.set(i, max);
    }
  }

  private void fixMarkersAfterDelete(int startFrame, int endFrame) {
    int removed = endFrame - startFrame;
    List<Integer> out = new ArrayList<>();
    for (int m : markers) {
      if (m < startFrame)
        out.add(m);
      else if (m >= endFrame)
        out.add(m - removed);
      // маркеры внутри удалённого диапазона отбрасываем
    }
    Collections.sort(out);
    markers = out;
  }

  public void fadeInRange(int startFrame, int endFrame, int fadeFrames, boolean applyL,
      boolean applyR) {
    pushHistory();
    pcm = AudioOps.fadeInRange(pcm, startFrame, endFrame, fadeFrames, applyL, applyR);
  }

  public void fadeOutRange(int startFrame, int endFrame, int fadeFrames, boolean applyL,
      boolean applyR) {
    pushHistory();
    pcm = AudioOps.fadeOutRange(pcm, startFrame, endFrame, fadeFrames, applyL, applyR);
  }

  /**
   * Сводит выделенный фрагмент в моно, дублируя результат в L и R.
   */
  public void mixToMonoRange(int startFrame, int endFrame) {
    pushHistory();
    int total = frameCount();
    int s = Math.max(0, Math.min(startFrame, total));
    int e = Math.max(s, Math.min(endFrame, total));
    if (e <= s)
      return;

    // Вырезаем кусок
    float[] range = AudioOps.trim(pcm, s, e);

    // Сводим в моно (умный алгоритм)
    float[] mono = AudioOps.toMonoSmart(range);

    // Дублируем в оба канала
    for (int i = 0; i < mono.length; i++) {
      pcm[(s + i) * 2] = mono[i];
      pcm[(s + i) * 2 + 1] = mono[i];
    }
  }

  /** Пиковая нормализация диапазона к target. Остальное не трогаем. */
  public void normalizeRangePeak(int startFrame, int endFrame, float target) {
    pushHistory();
    int total = frameCount();
    int s = Math.max(0, Math.min(startFrame, total));
    int e = Math.max(s, Math.min(endFrame, total));
    float peak = 0f;
    for (int i = s * CHANNELS; i < e * CHANNELS; i++) {
      float a = Math.abs(pcm[i]);
      if (a > peak)
        peak = a;
    }
    if (peak <= 1e-9f)
      return;
    float mul = target / peak;
    for (int i = s * CHANNELS; i < e * CHANNELS; i++)
      pcm[i] *= mul;
  }

  /** Сохранить текущий PCM в исходный файл (dir), как WAV 16-bit stereo @ sampleRate. */
  public void save() throws java.io.IOException {
    if (dir == null)
      throw new java.io.IOException("No file");
    com.grinya.voicesampleprep.audio.WavWriter.write(dir, pcm, sampleRate, sampleRate, 16, false,
        false, false, false, false);

    // Сохраняем .meta (маркеры + текущие параметры)
    AudioMeta meta = new AudioMeta();
    meta.sampleRate = sampleRate;
    meta.channels = CHANNELS;
    meta.bits = 16;
    meta.format = "wav";
    meta.markers = new ArrayList<>(markers);
    AudioMeta.save(dir, meta);
  }

  /**
   * Вставка нового звука. Если selStart != selEnd (есть выделение) — заменяем этот кусок. Если нет — просто вставляем в точку atFrame.
   */
  public void insert(float[] newPcm, int atFrame, int selStart, int selEnd) {
    pushHistory();

    int totalFrames = frameCount();
    int newFrames = newPcm.length / CHANNELS;

    int startReplace, endReplace;
    if (selStart >= 0 && selEnd > selStart) {
      startReplace = Math.max(0, Math.min(selStart, totalFrames));
      endReplace = Math.max(startReplace, Math.min(selEnd, totalFrames));
    } else {
      startReplace = Math.max(0, Math.min(atFrame, totalFrames));
      endReplace = startReplace; // ничего не удаляем, только вставляем
    }

    int removedFrames = endReplace - startReplace;
    int newTotalFrames = totalFrames - removedFrames + newFrames;
    float[] out = new float[newTotalFrames * CHANNELS];

    // 1. Копируем начало
    System.arraycopy(pcm, 0, out, 0, startReplace * CHANNELS);
    // 2. Вставляем новый кусок
    System.arraycopy(newPcm, 0, out, startReplace * CHANNELS, newPcm.length);
    // 3. Копируем остаток
    System.arraycopy(pcm, endReplace * CHANNELS, out, (startReplace + newFrames) * CHANNELS,
        (totalFrames - endReplace) * CHANNELS);

    pcm = out;

    // Обновляем маркеры
    int delta = newFrames - removedFrames;
    List<Integer> newMarkers = new ArrayList<>();

    for (int m : markers) {
      if (m < startReplace) {
        // до места вставки — без изменений
        newMarkers.add(m);
      } else if (m >= endReplace) {
        // после — сдвигаем
        newMarkers.add(m + delta);
      }
      // маркеры внутри заменяемого диапазона удаляются (мы их просто не добавляем)
    }
    Collections.sort(newMarkers);
    markers = newMarkers;
  }
}