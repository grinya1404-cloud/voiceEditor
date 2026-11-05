package com.grinya.voicesampleprep.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * Простой плеер для interleaved stereo float[] @ 48 кГц.
 * Воспроизводит в отдельном потоке, шлёт позицию в мс через callback.
 */
public class AudioPlayer {

    public interface Listener {
        /** Позиция воспроизведения в мс. Вызывается с UI-потока ~30 раз/сек. */
        void onPosition(int positionMs);
        /** Достигнут конец файла. */
        void onEnded();
        /** Воспроизведение запущено/остановлено (для смены иконки). */
        void onStateChanged(boolean playing);
    }

    private static final int RATE = 48000;
    private static final int CHANNELS = 2;

    private AudioTrack track;
    private float[] pcm;
    private int totalFrames;

    private volatile boolean playing = false;
    private volatile int startFrame = 0;
    private volatile int endFrame = 0;   // эксклюзивно; 0 = до конца
    private volatile int currentFrame = 0;

    private Thread playerThread;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable positionTick;
    private Listener listener;

    public AudioPlayer() {
        positionTick = new Runnable() {
            @Override
            public void run() {
                if (listener != null && playing) {
                    listener.onPosition(currentFrame * 1000 / RATE);
                    ui.postDelayed(this, 33);
                }
            }
        };
    }

    public void setListener(Listener l) { this.listener = l; }

    /** Установить PCM. Сбрасывает позицию. */
    public void setPcm(float[] pcm) {
        stopInternal();
        this.pcm = pcm;
        this.totalFrames = pcm == null ? 0 : pcm.length / CHANNELS;
        this.currentFrame = 0;
        this.startFrame = 0;
        this.endFrame = 0;
    }

    public int getCurrentFrame() { return currentFrame; }
    public int getTotalFrames() { return totalFrames; }
    public boolean isPlaying() { return playing; }

    /** Установить позицию (мс). */
    public void seekToMs(int ms) {
        int f = (int) ((long) ms * RATE / 1000);
        seekToFrame(f);
    }

    public void seekToFrame(int frame) {
        boolean wasPlaying = playing;
        if (wasPlaying) pause();
        currentFrame = Math.max(0, Math.min(totalFrames, frame));
        if (wasPlaying) play();
        else notifyPosition();
    }

    /** Воспроизвести с текущей позиции до конца (или до endFrame). */
    public void play() {
        if (pcm == null || totalFrames == 0) return;
        if (playing) return;

        if (currentFrame >= totalFrames) currentFrame = 0;
        startFrame = currentFrame;
        if (endFrame <= startFrame) endFrame = totalFrames;

        playing = true;

        playerThread = new Thread(() -> {
            int minBuf = AudioTrack.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT);
            int bufSize = Math.max(minBuf, 8192);

            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufSize * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            track = t;

            try {
                t.play();
                int chunk = bufSize / 2; // фреймов за раз
                int frame = startFrame;

                while (playing && frame < endFrame) {
                    int n = Math.min(chunk, endFrame - frame);
                    float[] block = new float[n * CHANNELS];
                    System.arraycopy(pcm, frame * CHANNELS, block, 0, n * CHANNELS);

                    int written = 0;
                    while (written < block.length && playing) {
                        int w = t.write(block, written, block.length - written,
                                AudioTrack.WRITE_BLOCKING);
                        if (w <= 0) break;
                        written += w;
                    }
                    frame += n;
                    currentFrame = frame;
                }
            } catch (Exception ignored) {
            } finally {
                playing = false;
                try { t.stop(); } catch (Exception ignored) {}
                try { t.release(); } catch (Exception ignored) {}
                if (track == t) track = null;

                final boolean reachedEnd = (currentFrame >= endFrame && endFrame > 0);
                ui.post(() -> {
                    if (listener != null) {
                        listener.onStateChanged(false);
                        if (reachedEnd) listener.onEnded();
                    }
                });
            }
        }, "AudioPlayer");
        playerThread.start();

        ui.removeCallbacks(positionTick);
        ui.post(positionTick);
        if (listener != null) listener.onStateChanged(true);
    }

    /** Пауза. Позиция сохраняется. */
    public void pause() {
        if (!playing) return;
        playing = false;
        ui.removeCallbacks(positionTick);
        // поток сам завершится, т.к. крутится на volatile playing
        if (listener != null) listener.onStateChanged(false);
    }

    /** Полная остановка, позиция в 0. */
    public void stop() {
        pause();
        currentFrame = 0;
        notifyPosition();
    }

    private void stopInternal() {
        playing = false;
        ui.removeCallbacks(positionTick);
    }

    private void notifyPosition() {
        if (listener != null) listener.onPosition(currentFrame * 1000 / RATE);
    }

    public void release() {
        stopInternal();
        this.listener = null;
        this.pcm = null;
    }
}