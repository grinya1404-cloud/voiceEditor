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

    public static void show(Context context, AudioClip clip) {
        if (clip == null || clip.pcm == null || clip.pcm.length == 0) {
            Toast.makeText(context, "Нет данных", Toast.LENGTH_SHORT).show();
            return;
        }

        // Запускаем анализ (может занять время на больших файлах)
        AudioInfo info = AudioInfo.analyze(clip.pcm, clip.sampleRate);
        String text = buildInfoText(clip, info);

        View view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_info, null);
        ((TextView) view.findViewById(R.id.txtInfo)).setText(text);

        new AlertDialog.Builder(context)
                .setTitle("Инфо")
                .setView(view)
                .setPositiveButton("OK", null)
                .show();
    }

    private static String buildInfoText(AudioClip clip, AudioInfo info) {
        StringBuilder sb = new StringBuilder();

        // Файл
        sb.append("=== ФАЙЛ ===\n");
        sb.append("Имя: ").append(clip.name).append("\n");
        if (clip.dir != null) {
            sb.append("Путь: ").append(clip.dir.getParent()).append("\n");
            sb.append("Размер: ").append(String.format(Locale.US, "%.1f КБ", clip.dir.length() / 1024.0)).append("\n");
        }
        sb.append("\n");

        // Параметры
        sb.append("=== ПАРАМЕТРЫ ===\n");
        sb.append("Sample Rate: ").append(clip.sampleRate).append(" Гц\n");
        sb.append("Каналы: ").append(clip.channels == 2 ? "Stereo" : "Mono").append("\n");
        sb.append("Фреймов: ").append(String.format(Locale.US, "%,d", info.totalFrames)).append("\n");

        int totalMs = info.totalFrames * 1000 / clip.sampleRate;
        int m = totalMs / 60000;
        int s = (totalMs % 60000) / 1000;
        int r = totalMs % 1000;
        sb.append("Длительность: ").append(String.format(Locale.US, "%02d:%02d.%03d", m, s, r)).append("\n");
        sb.append("\n");

        // Анализ
        sb.append("=== АНАЛИЗ СИГНАЛА ===\n");
        sb.append(String.format(Locale.US, "Peak: %.1f dB\n", info.peakDB));
        sb.append(String.format(Locale.US, "RMS:  %.1f dB\n", info.rmsDB));
        sb.append("Клиппинг: ").append(info.clipping ? "ДА (!)" : "нет").append("\n");
        sb.append(String.format(Locale.US, "Баланс L/R: %+.1f dB\n", info.balanceLR));
        sb.append("Тишина в начале: ").append(info.silenceStartMs).append(" мс\n");
        sb.append("Тишина в конце: ").append(info.silenceEndMs).append(" мс\n");
        sb.append("\n");

        // Маркеры
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

        return sb.toString();
    }
}