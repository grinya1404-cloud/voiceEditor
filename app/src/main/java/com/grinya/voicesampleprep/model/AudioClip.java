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
    // (тут цикл как в AudioOps.fadeIn, но с s/e)
    int total = frameCount();
    int s = Math.max(0, Math.min(startFrame, total));
    int e = Math.max(s, Math.min(endFrame, total));
    int n = Math.min(fadeFrames, e - s);
    if (n <= 0)
      return;
    for (int i = 0; i < n; i++) {
      float g = (float) i / n;
      int idx = (s + i) * CHANNELS;
      if (applyL)
        pcm[idx] *= g;
      if (applyR)
        pcm[idx + 1] *= g;
    }
  }

  public void fadeOutRange(int startFrame, int endFrame, int fadeFrames, boolean applyL,
      boolean applyR) {
    pushHistory();
    int total = frameCount();
    int s = Math.max(0, Math.min(startFrame, total));
    int e = Math.max(s, Math.min(endFrame, total));
    int n = Math.min(fadeFrames, e - s);
    if (n <= 0)
      return;
    for (int i = 0; i < n; i++) {
      float g = 1f - (float) i / n;
      int idx = (e - n + i) * CHANNELS;
      if (applyL)
        pcm[idx] *= g;
      if (applyR)
        pcm[idx + 1] *= g;
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
    com.grinya.voicesampleprep.audio.WavWriter.write(dir, pcm, sampleRate, sampleRate, 16, false);
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