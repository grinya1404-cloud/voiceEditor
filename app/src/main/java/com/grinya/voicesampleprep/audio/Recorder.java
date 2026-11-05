package com.grinya.voicesampleprep.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Запись с микрофона: стерео 48 кГц, 16-bit PCM во float[].
 * Пишет в память. Отдаёт уровень (пик) для UI.
 */
public class Recorder {

    public interface Listener {
        /** Пиковый уровень 0..1 (RMS по блоку), шлётся ~30 раз/сек с UI-потока. */
        void onLevel(float rms);
        /** Запись завершена. elapsedMs — длительность. */
        void onFinished(float[] pcm, int elapsedMs);
        /** Ошибка. */
        void onError(String msg);
    }

    private static final int RATE = 48000;
    private static final int CHANNELS = 2;

    private AudioRecord record;
    private Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Listener listener;
    private long startTime;

    public void setListener(Listener l) { this.listener = l; }

    public boolean isRecording() { return running.get(); }

    public void start() {
        if (running.get()) return;

        int minBuf = AudioRecord.getMinBufferSize(
                RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf < 0) {
            if (listener != null) listener.onError("getMinBufferSize: " + minBuf);
            return;
        }
        int bufSize = Math.max(minBuf, 16384);

        try {
            record = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize);
        } catch (Exception e) {
            if (listener != null) listener.onError("AudioRecord init: " + e.getMessage());
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            record = null;
            if (listener != null) listener.onError("AudioRecord not initialized");
            return;
        }

        running.set(true);
        startTime = System.currentTimeMillis();

        final int readBuf = bufSize / 2; // shorts
        final short[] tmp = new short[readBuf];

        thread = new Thread(() -> {
            record.startRecording();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] byteBuf = new byte[readBuf * 2];

            while (running.get()) {
                int n = record.read(tmp, 0, tmp.length);
                if (n <= 0) continue;

                // пишем в byte buffer для скорости
                for (int i = 0; i < n; i++) {
                    short s = tmp[i];
                    byteBuf[i * 2] = (byte) (s & 0xFF);
                    byteBuf[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                }
                bos.write(byteBuf, 0, n * 2);

                // RMS для индикатора (сэмплируем каждый 4-й сэмпл для скорости)
                double sum = 0;
                int count = 0;
                for (int i = 0; i < n; i += 4) {
                    float v = tmp[i] / 32768f;
                    sum += v * v;
                    count++;
                }
                final float rms = count > 0 ? (float) Math.sqrt(sum / count) : 0f;
                ui.post(() -> { if (listener != null) listener.onLevel(rms); });
            }

            try { record.stop(); } catch (Exception ignored) {}
            record.release();
            record = null;

            int elapsed = (int) (System.currentTimeMillis() - startTime);

            // конвертируем byte[] (LE 16-bit interleaved stereo) в float[]
            byte[] bytes = bos.toByteArray();
            int sampleCount = bytes.length / 2;
            float[] pcm = new float[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                short s = (short) (((bytes[i * 2 + 1] & 0xFF) << 8) | (bytes[i * 2] & 0xFF));
                pcm[i] = s / 32768f;
            }

            final float[] result = pcm;
            ui.post(() -> { if (listener != null) listener.onFinished(result, elapsed); });
        }, "AudioRecorder");
        thread.start();
    }

    public void stop() {
        running.set(false);
    }

    public void release() {
        running.set(false);
        this.listener = null;
    }
}