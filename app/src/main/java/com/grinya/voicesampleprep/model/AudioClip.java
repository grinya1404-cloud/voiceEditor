package com.grinya.voicesampleprep.model;

import com.grinya.voicesampleprep.edit.UndoStack;
import com.grinya.voicesampleprep.util.AudioOps;
import com.grinya.voicesampleprep.audio.WavWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.IOException;

public class AudioClip {

  public static final int CHANNELS = 2;
  public static final int SAMPLE_RATE = 48000;
  public java.io.File dir;

  public String name;
  public float[] pcm; // interleaved stereo L,R,L,R...
  public int sampleRate;
  public int channels;
  public List<Integer> markers = new ArrayList<>(); // индексы фреймов
  public List<int[]> exportIntervals = new ArrayList<>();

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
    fixExportIntervalsAfterTrim(startFrame, endFrame);
  }

  /**
   * Автоматически обрезает тишину в начале и конце.
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

    float[] range = AudioOps.trim(pcm, s, e);
    int rangeFrames = range.length / CHANNELS;

    float[] trimmed = AudioOps.trimSilence(range, threshold, CHANNELS);
    int trimmedFrames = trimmed.length / CHANNELS;
    if (trimmed.length == 0 || trimmed.length == range.length)
      return;

    // Сколько тишины обрезано в начале/конце (в фреймах)
    int startSilence = 0;
    for (int i = 0; i < rangeFrames; i++) {
      boolean silent = true;
      for (int c = 0; c < CHANNELS; c++) {
        if (Math.abs(range[i * CHANNELS + c]) >= threshold) {
          silent = false;
          break;
        }
      }
      if (!silent)
        break;
      startSilence++;
    }
    int endSilence = rangeFrames - trimmedFrames - startSilence;
    if (endSilence < 0)
      endSilence = 0;

    float[] newPcm = new float[pcm.length - range.length + trimmed.length];
    System.arraycopy(pcm, 0, newPcm, 0, s * CHANNELS);
    System.arraycopy(trimmed, 0, newPcm, s * CHANNELS, trimmed.length);
    System.arraycopy(pcm, e * CHANNELS, newPcm, (s * CHANNELS) + trimmed.length,
        (total - e) * CHANNELS);

    pushHistory();
    pcm = newPcm;

    // --- Пересчёт маркеров: прилипание к границе + дедуп ---
    int newStart = s;
    int newEnd = s + trimmedFrames - 1;
    if (newEnd < newStart)
      newEnd = newStart;

    java.util.TreeSet<Integer> outMarkers = new java.util.TreeSet<>();
    for (int m : markers) {
      outMarkers.add(remapFrame(m, s, e, startSilence, endSilence, rangeFrames, newStart, newEnd));
    }
    markers = new ArrayList<>(outMarkers);

    // --- Пересчёт интервалов: та же логика, схлопнувшиеся удаляем ---
    java.util.TreeSet<String> seen = new java.util.TreeSet<>();
    java.util.List<int[]> outIntervals = new java.util.ArrayList<>();
    for (int[] iv : exportIntervals) {
      int a = remapFrame(iv[0], s, e, startSilence, endSilence, rangeFrames, newStart, newEnd);
      int b = remapFrame(iv[1], s, e, startSilence, endSilence, rangeFrames, newStart, newEnd);
      if (b <= a)
        continue;
      String key = a + ":" + b;
      if (seen.add(key))
        outIntervals.add(new int[] { a, b });
    }
    exportIntervals = outIntervals;
  }

  /** Пересчёт одного фрейма после autoTrim. */
  private static int remapFrame(int m, int s, int e, int startSilence, int endSilence,
      int rangeFrames, int newStart, int newEnd) {
    if (m < s)
      return m;
    if (m >= e)
      return m - (startSilence + endSilence);
    int local = m - s;
    if (local < startSilence)
      return newStart;
    if (local >= rangeFrames - endSilence)
      return newEnd;
    return s + local - startSilence;
  }

  public void delete(int startFrame, int endFrame) {
    pushHistory();
    pcm = AudioOps.deleteRange(pcm, startFrame, endFrame);
    fixMarkersAfterDelete(startFrame, endFrame);
    fixExportIntervalsAfterDelete(startFrame, endFrame);
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
    if (applyL && applyR) {
      pcm = AudioOps.normalizePeak(pcm, target);
    } else {
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

    int s = Math.max(0, Math.min(startFrame, frameCount()));
    int e = Math.max(s, Math.min(endFrame, frameCount()));
    List<Integer> newMarkers = new ArrayList<>();
    for (int m : markers) {
      if (m >= s && m < e) {
        newMarkers.add(s + (e - 1 - m));
      } else {
        newMarkers.add(m);
      }
    }
    Collections.sort(newMarkers);
    markers = newMarkers;
  }

  public void invertPhase(int startFrame, int endFrame, boolean applyL, boolean applyR) {
    pushHistory();
    pcm = AudioOps.invertPhase(pcm, startFrame, endFrame, applyL, applyR);
  }

  /** Делегат для обратной совместимости. */
  public void invertPhaseRange(int startFrame, int endFrame, boolean applyL, boolean applyR) {
    invertPhase(startFrame, endFrame, applyL, applyR);
  }

  public void setPcm(float[] newPcm) {
    pushHistory();
    pcm = newPcm;
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

  public void highPass(int startFrame, int endFrame, float cutoffHz, boolean applyL,
      boolean applyR) {
    pushHistory();
    int total = frameCount();
    int s = Math.max(0, Math.min(startFrame, total));
    int e = Math.max(s, Math.min(endFrame, total));
    if (e <= s)
      return;
    float[] range = AudioOps.trim(pcm, s, e);
    range = AudioOps.highPass(range, sampleRate, cutoffHz, applyL, applyR);
    System.arraycopy(range, 0, pcm, s * CHANNELS, range.length);
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

    float[] range = AudioOps.trim(pcm, s, e);
    float[] mono = AudioOps.toMonoSmart(range);

    for (int i = 0; i < mono.length; i++) {
      pcm[(s + i) * CHANNELS] = mono[i];
      pcm[(s + i) * CHANNELS + 1] = mono[i];
    }
  }

  public void swapChannels(int startFrame, int endFrame) {
    pushHistory();
    int total = frameCount();
    int s = Math.max(0, Math.min(startFrame, total));
    int e = Math.max(s, Math.min(endFrame, total));
    for (int i = s; i < e; i++) {
      int idx = i * CHANNELS;
      float tmp = pcm[idx];
      pcm[idx] = pcm[idx + 1];
      pcm[idx + 1] = tmp;
    }
  }

  public void copyLtoR(int startFrame, int endFrame) {
    pushHistory();
    int total = frameCount();
    int s = Math.max(0, Math.min(startFrame, total));
    int e = Math.max(s, Math.min(endFrame, total));
    for (int i = s; i < e; i++) {
      pcm[i * CHANNELS + 1] = pcm[i * CHANNELS];
    }
  }

  public void copyRtoL(int startFrame, int endFrame) {
    pushHistory();
    int total = frameCount();
    int s = Math.max(0, Math.min(startFrame, total));
    int e = Math.max(s, Math.min(endFrame, total));
    for (int i = s; i < e; i++) {
      pcm[i * CHANNELS] = pcm[i * CHANNELS + 1];
    }
  }
    // ---------- undo/redo ----------

  private void pushHistory() {
    history.push(pcm.clone(), markers, exportIntervals);
  }

  public boolean canUndo() {
    return history.canUndo();
  }

  public boolean canRedo() {
    return history.canRedo();
  }

  public void undo() {
    UndoStack.Snapshot s = history.undo(pcm, markers, exportIntervals);
    if (s != null) {
      pcm = s.pcm;
      markers = s.markers;
      exportIntervals = s.exportIntervals;
    }
  }

  public void redo() {
    UndoStack.Snapshot s = history.redo(pcm, markers, exportIntervals);
    if (s != null) {
      pcm = s.pcm;
      markers = s.markers;
      exportIntervals = s.exportIntervals;
    }
  }

  // ---------- маркеры ----------

  public void addMarker(int frame) {
    frame = Math.max(0, Math.min(frameCount() - 1, frame));
    if (!markers.contains(frame)) {
      pushHistory();
      markers.add(frame);
      Collections.sort(markers);
    }
  }

  public void removeMarker(int frame) {
    if (markers.contains(frame)) {
      pushHistory();
      markers.remove(Integer.valueOf(frame));
    }
  }

  public void clearMarkers() {
    if (!markers.isEmpty()) {
      pushHistory();
      markers.clear();
    }
  }

  // ---------- коррекция маркеров ----------

  private void fixMarkersAfterTrim(int startFrame, int endFrame) {
    int span = endFrame - startFrame;
    List<Integer> out = new ArrayList<>();
    for (int m : markers) {
      if (m >= startFrame && m < endFrame)
        out.add(m - startFrame);
    }
    Collections.sort(out);
    markers = out;
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
    }
    Collections.sort(out);
    markers = out;
  }

  // ---------- коррекция интервалов (F12) ----------

  private void fixExportIntervalsAfterTrim(int startFrame, int endFrame) {
    int span = endFrame - startFrame;
    List<int[]> out = new ArrayList<>();
    for (int[] iv : exportIntervals) {
      int a = Math.max(iv[0], startFrame) - startFrame;
      int b = Math.min(iv[1], endFrame) - startFrame;
      if (b > a) {
        if (b > span) b = span;
        if (a < 0) a = 0;
        out.add(new int[] { a, b });
      }
    }
    exportIntervals = out;
  }

  private void fixExportIntervalsAfterDelete(int startFrame, int endFrame) {
    int removed = endFrame - startFrame;
    List<int[]> out = new ArrayList<>();
    for (int[] iv : exportIntervals) {
      int a = iv[0];
      int b = iv[1];
      if (b <= startFrame) {
        out.add(new int[] { a, b });
      } else if (a >= endFrame) {
        out.add(new int[] { a - removed, b - removed });
      } else {
        int na = a < startFrame ? a : startFrame;
        int nb = b > endFrame ? b - removed : startFrame;
        if (nb > na)
          out.add(new int[] { na, nb });
      }
    }
    exportIntervals = out;
  }

  // ---------- интервалы экспорта ----------

  /** Добавить интервал для экспорта. Возвращает true, если добавлен. */
  public boolean addExportInterval(int startMarker, int endMarker) {
    if (startMarker >= endMarker)
      return false;
    for (int[] i : exportIntervals) {
      if (i[0] == startMarker && i[1] == endMarker)
        return false;
    }
    pushHistory();
    exportIntervals.add(new int[] { startMarker, endMarker });
    return true;
  }

  /** Удалить интервал по началу и концу. */
  public void removeExportInterval(int startMarker, int endMarker) {
    boolean changed = false;
    java.util.Iterator<int[]> it = exportIntervals.iterator();
    while (it.hasNext()) {
      int[] i = it.next();
      if (i[0] == startMarker && i[1] == endMarker) {
        it.remove();
        changed = true;
      }
    }
    if (changed)
      pushHistory();
  }

  /** Удалить все интервалы, где участвует маркер. */
  public void removeExportIntervalsWithMarker(int marker) {
    boolean changed = false;
    java.util.Iterator<int[]> it = exportIntervals.iterator();
    while (it.hasNext()) {
      int[] i = it.next();
      if (i[0] == marker || i[1] == marker) {
        it.remove();
        changed = true;
      }
    }
    if (changed)
      pushHistory();
  }

  /** Очистить все интервалы. */
  public void clearExportIntervals() {
    if (!exportIntervals.isEmpty()) {
      pushHistory();
      exportIntervals.clear();
    }
  }

  // ---------- вставка ----------

  /**
   * Вставка нового звука. Если selStart != selEnd (есть выделение) — заменяем этот кусок.
   * Если нет — просто вставляем в точку atFrame.
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
      endReplace = startReplace;
    }

    int removedFrames = endReplace - startReplace;
    int newTotalFrames = totalFrames - removedFrames + newFrames;
    float[] out = new float[newTotalFrames * CHANNELS];

    System.arraycopy(pcm, 0, out, 0, startReplace * CHANNELS);
    System.arraycopy(newPcm, 0, out, startReplace * CHANNELS, newPcm.length);
    System.arraycopy(pcm, endReplace * CHANNELS, out, (startReplace + newFrames) * CHANNELS,
        (totalFrames - endReplace) * CHANNELS);

    pcm = out;

    int delta = newFrames - removedFrames;
    List<Integer> newMarkers = new ArrayList<>();
    for (int m : markers) {
      if (m < startReplace)
        newMarkers.add(m);
      else if (m >= endReplace)
        newMarkers.add(m + delta);
    }
    Collections.sort(newMarkers);
    markers = newMarkers;

    // Интервалы: концы < startReplace — без изменений; >= endReplace — сдвиг на delta;
    // внутри заменяемого — интервал схлопывается/удаляется.
    List<int[]> newIntervals = new ArrayList<>();
    for (int[] iv : exportIntervals) {
      int a = iv[0];
      int b = iv[1];
      int na, nb;
      if (a < startReplace) na = a;
      else if (a >= endReplace) na = a + delta;
      else na = startReplace;

      if (b < startReplace) nb = b;
      else if (b >= endReplace) nb = b + delta;
      else nb = startReplace + newFrames;

      if (nb > na)
        newIntervals.add(new int[] { na, nb });
    }
    exportIntervals = newIntervals;
  }

  // ---------- сохранение ----------

  /** Сохранить текущий PCM в исходный файл (dir), как WAV 16-bit stereo @ sampleRate. */
  public void save() throws IOException {
    if (dir == null)
      throw new IOException("No file");
    WavWriter.write(dir, pcm, sampleRate, sampleRate, 16, false, false, false, false, false);

    AudioMeta meta = AudioMeta.load(dir);
    meta.markers = new ArrayList<>(markers);
    meta.exportIntervals = new ArrayList<>();
    for (int[] i : exportIntervals)
      meta.exportIntervals.add(new int[] { i[0], i[1] });
    AudioMeta.save(dir, meta);
  }
}