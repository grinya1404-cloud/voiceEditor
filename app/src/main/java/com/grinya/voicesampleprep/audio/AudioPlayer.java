package com.grinya.voicesampleprep.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * Надежный плеер для interleaved stereo float[] @ 48 кГц.
 */
public class AudioPlayer {

  public interface Listener {
    void onPosition(int positionMs);

    void onEnded();

    void onStateChanged(boolean playing);
  }

  private static final int RATE = 48000;
  private static final int CHANNELS = 2;

  private AudioTrack track;
  private float[] pcm;
  private int totalFrames;

  private volatile boolean playing = false;
  private volatile int currentFrame = 0; // Текущая позиция (для интерфейса)
  private int endFrame = 0; // До какого фрейма играть

  private Thread playerThread;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final Runnable positionTick;
  private Listener listener;

  private long playStartTimeMs = 0;
  private int playStartFrame = 0;

  private long lastTimestampNanos = 0;
  private long lastTimestampFrame = 0;
  private boolean hasValidTimestamp = false;
  private int streamStartFrame = 0;

  private boolean playLeft = true;
  private boolean playRight = true;
  private int playGeneration = 0;

  public AudioPlayer() {
    positionTick = new Runnable() {
      @Override
      public void run() {
        if (listener != null && playing) {
          int frameToReport;

          if (hasValidTimestamp) {
            // Время, прошедшее с последнего замера
            long now = System.nanoTime();
            long elapsedNanos = now - lastTimestampNanos;
            long framesAdvanced = (elapsedNanos * RATE) / 1_000_000_000L;

            // Прибавляем позицию AudioTrack к тому месту, откуда мы начали играть
            long totalFrames = lastTimestampFrame + framesAdvanced;
            frameToReport = streamStartFrame + (int) totalFrames;
          } else {
            // Если таймстамп ещё не получен (или не поддерживается) — используем старый способ
            int elapsedMs = (int) (System.currentTimeMillis() - playStartTimeMs);
            frameToReport = playStartFrame + (elapsedMs * RATE / 1000);
          }

          if (frameToReport > endFrame)
            frameToReport = endFrame;
          if (frameToReport < 0)
            frameToReport = 0;

          currentFrame = frameToReport;
          listener.onPosition(currentFrame * 1000 / RATE);
          ui.postDelayed(this, 16);
        }
      }
    };
  }

  public void setListener(Listener l) {
    this.listener = l;
  }

  public void setPcm(float[] pcm) {
    pause();
    this.pcm = pcm;
    this.totalFrames = (pcm == null) ? 0 : pcm.length / CHANNELS;
    this.currentFrame = 0;
    this.endFrame = totalFrames;
  }

  public void setChannelMask(boolean left, boolean right) {
    this.playLeft = left;
    this.playRight = right;
  }

  public void setPlayRange(int startFrame, int endFrame) {
    this.endFrame = Math.min(totalFrames, endFrame);
    if (this.endFrame <= 0)
      this.endFrame = totalFrames;
    // Если текущая позиция вне диапазона — ставим в начало
    if (this.currentFrame < startFrame || this.currentFrame >= this.endFrame) {
      this.currentFrame = Math.max(0, startFrame);
    }
  }

  public int getCurrentFrame() {
    return currentFrame;
  }

  public int getTotalFrames() {
    return totalFrames;
  }

  public boolean isPlaying() {
    return playing;
  }

  public void seekToFrame(int frame) {
    android.util.Log.d("PLAYER", "seekToFrame(" + frame + ") called. wasPlaying=" + playing
        + " currentFrame=" + currentFrame);
    boolean wasPlaying = playing;
    pause();
    currentFrame = Math.max(0, Math.min(endFrame, frame));
    playStartFrame = currentFrame; // ← важно!
    playStartTimeMs = System.currentTimeMillis();
    notifyPosition();
    if (wasPlaying)
      play();
  }

  public void play() {
    android.util.Log.d("PLAYER", "play() called. currentFrame=" + currentFrame + " endFrame="
        + endFrame + " totalFrames=" + totalFrames);

    if (pcm == null || totalFrames == 0)
      return;
    if (playing)
      return;

    // Ждем завершения предыдущего потока
    if (playerThread != null && playerThread.isAlive()) {
      try {
        playerThread.join(500);
      } catch (InterruptedException ignored) {
      }
    }

    // Увеличиваем поколение ТОЛЬКО когда точно стартуем
    final int myGeneration = ++playGeneration;

    // Корректируем позицию
    if (currentFrame >= totalFrames)
      currentFrame = 0;
    if (endFrame > 0 && currentFrame >= endFrame)
      currentFrame = playStartFrame;

    playStartFrame = currentFrame;
    streamStartFrame = currentFrame;
    playStartTimeMs = System.currentTimeMillis();
    playing = true;

    int framesToPlay = endFrame - playStartFrame;
    double durationSec = (double) framesToPlay / RATE;

    playerThread = new Thread(() -> {
      AudioTrack t;

      if (durationSec < 10.0) {
        // --- КОРОТКИЙ ФАЙЛ ---
        int numFrames = framesToPlay;
        float[] data = new float[numFrames * CHANNELS];
        System.arraycopy(pcm, playStartFrame * CHANNELS, data, 0, numFrames * CHANNELS);

        for (int i = 0; i < data.length; i += 2) {
          if (!playLeft)
            data[i] = 0;
          if (!playRight)
            data[i + 1] = 0;
        }

        t = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(numFrames * CHANNELS * 4).setTransferMode(AudioTrack.MODE_STATIC)
            .build();

        track = t;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(data.length * 4);
        bb.order(java.nio.ByteOrder.nativeOrder());
        bb.asFloatBuffer().put(data);
        t.write(bb, data.length * 4, AudioTrack.WRITE_BLOCKING);
        t.play();

        long startTime = System.currentTimeMillis();
        long durationMs = (long) (durationSec * 1000);

        while (playing && myGeneration == playGeneration) {
          try {
            Thread.sleep(20);
          } catch (InterruptedException ignored) {
          }
          long elapsed = System.currentTimeMillis() - startTime;
          if (elapsed >= durationMs)
            break;
        }

        try {
          t.stop();
        } catch (Exception ignored) {
        }
        try {
          t.release();
        } catch (Exception ignored) {
        }
        if (track == t)
          track = null;

        // Финалим позицию только если мы еще актуальны
        if (myGeneration == playGeneration) {
          playing = false;
          if (currentFrame < endFrame)
            currentFrame = endFrame;
        }

        ui.post(() -> {
          if (myGeneration != playGeneration)
            return; // старый поток молчит
          if (listener != null) {
            listener.onStateChanged(false);
            listener.onPosition(currentFrame * 1000 / RATE);
            if (currentFrame >= endFrame)
              listener.onEnded();
          }
        });

      } else {
        // --- ДЛИННЫЙ ФАЙЛ ---
        int minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT);
        int bufSize = Math.max(minBuf, 65536);

        t = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(bufSize).setTransferMode(AudioTrack.MODE_STREAM).build();

        track = t;
        t.play();

        Thread syncThread = new Thread(() -> {
          android.media.AudioTimestamp ts = new android.media.AudioTimestamp();
          while (playing && myGeneration == playGeneration) {
            if (t.getTimestamp(ts)) {
              lastTimestampNanos = ts.nanoTime;
              lastTimestampFrame = ts.framePosition;
              hasValidTimestamp = true;
            }
            try {
              Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
          }
        }, "AudioSync");
        syncThread.start();

        int chunk = bufSize / 4 / CHANNELS;
        if (chunk <= 0)
          chunk = 1024;
        int frame = playStartFrame;

        while (frame < endFrame && playing && myGeneration == playGeneration) {
          int n = Math.min(chunk, endFrame - frame);
          float[] block = new float[n * CHANNELS];
          System.arraycopy(pcm, frame * CHANNELS, block, 0, n * CHANNELS);

          for (int i = 0; i < block.length; i += 2) {
            if (!playLeft)
              block[i] = 0;
            if (!playRight)
              block[i + 1] = 0;
          }
          int written = 0;
          int totalToWrite = block.length;
          while (written < totalToWrite && playing && myGeneration == playGeneration) {
            int w = t.write(block, written, totalToWrite - written, AudioTrack.WRITE_BLOCKING);
            if (w < 0) {
              android.util.Log.e("PLAYER", "write error=" + w);
              break;
            }
            written += w;
          }
          frame += n;
        }

        hasValidTimestamp = false;
        try {
          Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
        try {
          t.stop();
        } catch (Exception ignored) {
        }
        try {
          t.release();
        } catch (Exception ignored) {
        }
        if (track == t)
          track = null;

        if (myGeneration == playGeneration) {
          playing = false;
          currentFrame = Math.min(currentFrame, endFrame);
        }

        ui.post(() -> {
          if (myGeneration != playGeneration)
            return;
          if (listener != null) {
            listener.onStateChanged(false);
            listener.onPosition(currentFrame * 1000 / RATE);
            if (currentFrame >= endFrame)
              listener.onEnded();
          }
        });
      }
    }, "AudioPlayer");

    playerThread.start();
    ui.post(positionTick);
    if (listener != null)
      listener.onStateChanged(true);
  }

  public void pause() {
    android.util.Log.d("PLAYER",
        "pause() called. playing=" + playing + " currentFrame=" + currentFrame);
    if (!playing)
      return;
    playing = false;
    playGeneration++;
    ui.removeCallbacks(positionTick);

    // Обновляем currentFrame точным значением на момент паузы
    int elapsedMs = (int) (System.currentTimeMillis() - playStartTimeMs);
    currentFrame = Math.min(playStartFrame + (elapsedMs * RATE / 1000), endFrame);

    if (listener != null)
      listener.onStateChanged(false);
  }

  public void stop() {
    pause();
    currentFrame = 0;
    notifyPosition();
  }

  private void notifyPosition() {
    if (listener != null)
      listener.onPosition(currentFrame * 1000 / RATE);
  }

  public void release() {
    playing = false;
    ui.removeCallbacks(positionTick);
    this.listener = null;
    this.pcm = null;
  }
}