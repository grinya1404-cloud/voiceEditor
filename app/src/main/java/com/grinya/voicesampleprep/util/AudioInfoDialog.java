package com.grinya.voicesampleprep.util;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.grinya.voicesampleprep.R;
import com.grinya.voicesampleprep.model.AudioClip;

import java.util.Locale;

public class AudioInfoDialog {

  public static void show(Context context, AudioClip clip, int selStart, int selEnd) {
    if (clip == null || clip.pcm == null || clip.pcm.length == 0) {
      Toast.makeText(context, "Нет данных", Toast.LENGTH_SHORT).show();
      return;
    }

    // 1. Анализ всего файла
    AudioInfo fullInfo = AudioInfo.analyze(clip.pcm, clip.sampleRate);

    // 2. Анализ выделения (если есть)
    AudioInfo selInfo = null;
    float[] selPcm = null;
    if (selStart >= 0 && selEnd > selStart) {
      int total = clip.frameCount();
      int s = Math.max(0, Math.min(selStart, total));
      int e = Math.max(s, Math.min(selEnd, total));
      if (e > s) {
        selPcm = com.grinya.voicesampleprep.util.AudioOps.trim(clip.pcm, s, e);
        selInfo = AudioInfo.analyze(selPcm, clip.sampleRate);
      }
    }

    String text = buildInfoText(clip, fullInfo, selInfo, selStart, selEnd);

    View view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_info, null);
    ((TextView) view.findViewById(R.id.txtInfo)).setText(text);

    new AlertDialog.Builder(context).setTitle("Инфо").setView(view).setPositiveButton("OK", null)
        .show();
  }

  private static String buildInfoText(AudioClip clip, AudioInfo fullInfo, AudioInfo selInfo,
      int selStart, int selEnd) {
    StringBuilder sb = new StringBuilder();

    // --- Блок выделения (если есть) ---
    if (selInfo != null) {
      int selMs = (selEnd - selStart) * 1000 / clip.sampleRate;
      int mm = selMs / 60000;
      int ss = (selMs % 60000) / 1000;
      int rr = selMs % 1000;

      sb.append("=== ВЫДЕЛЕНИЕ (").append(String.format(Locale.US, "%02d:%02d.%03d", mm, ss, rr))
          .append(") ===\n");
      sb.append(String.format(Locale.US, "Peak: %.1f dB\n", selInfo.peakDB));
      sb.append(String.format(Locale.US, "RMS:  %.1f dB\n", selInfo.rmsDB));
      sb.append("Клиппинг: ").append(selInfo.clipping ? "ДА (!)" : "нет").append("\n");
      sb.append("\n");
    }

    // --- Блок файла ---
    sb.append("=== ФАЙЛ ===\n");
    sb.append("Имя: ").append(clip.name).append("\n");
    if (clip.dir != null) {
      sb.append("Путь: ").append(clip.dir.getParent()).append("\n");
      sb.append("Размер: ").append(String.format(Locale.US, "%.1f КБ", clip.dir.length() / 1024.0))
          .append("\n");
    }
    sb.append("\n");

    // --- Параметры ---
    sb.append("=== ПАРАМЕТРЫ ===\n");
    sb.append("Sample Rate: ").append(clip.sampleRate).append(" Гц\n");
    sb.append("Каналы: ").append(clip.channels == 2 ? "Stereo" : "Mono").append("\n");
    sb.append("Фреймов: ").append(String.format(Locale.US, "%,d", fullInfo.totalFrames))
        .append("\n");

    int totalMs = fullInfo.totalFrames * 1000 / clip.sampleRate;
    int m = totalMs / 60000;
    int s = (totalMs % 60000) / 1000;
    int r = totalMs % 1000;
    sb.append("Длительность: ").append(String.format(Locale.US, "%02d:%02d.%03d", m, s, r))
        .append("\n");
    sb.append("\n");

    // --- Анализ всего файла ---
    sb.append("=== АНАЛИЗ СИГНАЛА (ВЕСЬ ФАЙЛ) ===\n");
    sb.append(String.format(Locale.US, "Peak: %.1f dB\n", fullInfo.peakDB));
    sb.append(String.format(Locale.US, "RMS:  %.1f dB\n", fullInfo.rmsDB));
    sb.append("Клиппинг: ").append(fullInfo.clipping ? "ДА (!)" : "нет").append("\n");
    sb.append(String.format(Locale.US, "Баланс L/R: %+.1f dB\n", fullInfo.balanceLR));
    sb.append("Тишина в начале: ").append(fullInfo.silenceStartMs).append(" мс\n");
    sb.append("Тишина в конце: ").append(fullInfo.silenceEndMs).append(" мс\n");
    sb.append("\n");

    // --- Маркеры ---
    sb.append("=== МАРКЕРЫ ===\n");
    sb.append("Всего: ").append(clip.markers.size()).append("\n");
    int show = Math.min(10, clip.markers.size());
    for (int i = 0; i < show; i++) {
      int markerFrame = clip.markers.get(i);
      int markerMs = markerFrame * 1000 / clip.sampleRate;
      int mm = markerMs / 60000;
      int ss = (markerMs % 60000) / 1000;
      int rr = markerMs % 1000;
      sb.append(String.format(Locale.US, "  %d: %02d:%02d.%03d\n", i + 1, mm, ss, rr));
    }
    if (clip.markers.size() > 10) {
      sb.append("  ... и ещё ").append(clip.markers.size() - 10).append("\n");
    }
    // Исходный файл (из .meta)
    if (clip.dir != null) {
      com.grinya.voicesampleprep.model.AudioMeta meta = com.grinya.voicesampleprep.model.AudioMeta
          .load(clip.dir);

      sb.append("=== ИСХОДНЫЙ ФАЙЛ ===\n");
      if (meta.sampleRate > 0 || meta.channels > 0) {
        if (meta.sampleRate > 0)
          sb.append("Sample Rate: ").append(meta.sampleRate).append(" Гц\n");
        if (meta.channels > 0)
          sb.append("Каналы: ").append(meta.channels == 1 ? "Mono" : "Stereo").append("\n");
        if (meta.bits > 0)
          sb.append("Битность: ").append(meta.bits).append("-bit\n");
        if (meta.format != null && !meta.format.isEmpty())
          sb.append("Формат: ").append(meta.format).append("\n");
      } else {
        sb.append("Нет данных об исходнике\n");
      }
      sb.append("\n");
    }
    return sb.toString();
  }
}