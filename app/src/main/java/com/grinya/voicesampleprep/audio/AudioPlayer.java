package com.grinya.voicesampleprep.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * Простой плеер для interleaved stereo float[] @ 48 кГц. Воспроизводит в отдельном потоке, шлёт позицию в мс через callback.
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
  private volatile int endFrame = 0; // эксклюзивно; 0 = до конца
  private volatile int currentFrame = 0;

  private Thread playerThread;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final Runnable positionTick;
  private Listener listener;

  private long playStartTimeMs = 0;
  private int playStartFrame = 0;

  public AudioPlayer() {
    positionTick = new Runnable() {
      @Override
      public void run() {
        if (listener != null && playing) {
          int frame;
          if (track != null) {
            // getPlaybackHeadPosition возвращает позицию от начала буфера AudioTrack,
            // а не от startFrame — поэтому переводим в координаты трека.
            int head = track.getPlaybackHeadPosition();
            frame = startFrame + head;
            if (frame < startFrame)
              frame = startFrame;
            if (endFrame > 0 && frame > endFrame)
              frame = endFrame;
          } else {
            int elapsedMs = (int) (System.currentTimeMillis() - playStartTimeMs);
            frame = playStartFrame + elapsedMs * RATE / 1000;
            if (frame > endFrame)
              frame = endFrame;
          }
          currentFrame = frame;
          listener.onPosition(currentFrame * 1000 / RATE);
          ui.postDelayed(this, 16);
        }
      }
    };
  }

  public void setListener(Listener l) {
    this.listener = l;
  }

  /** Установить PCM. Сбрасывает позицию. */
  public void setPcm(float[] pcm) {
    stopInternal();
    this.pcm = pcm;
    this.totalFrames = pcm == null ? 0 : pcm.length / CHANNELS;
    this.currentFrame = 0;
    this.startFrame = 0;
    this.endFrame = 0;
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

  /** Установить позицию (мс). */
  public void seekToMs(int ms) {
    int f = (int) ((long) ms * RATE / 1000);
    seekToFrame(f);
  }

  public void seekToFrame(int frame) {
    boolean wasPlaying = playing;
    if (wasPlaying)
      pause();
    currentFrame = Math.max(0, Math.min(totalFrames, frame));
    if (wasPlaying)
      play();
    else
      notifyPosition();
  }

  /** Воспроизвести с текущей позиции до конца (или до endFrame). */
  public void play() {
    if (pcm == null || totalFrames == 0)
      return;
    if (playing)
      return;

    // Если playRange не задан (endFrame=0), играем от текущей позиции до конца
    if (endFrame <= 0) {
      startFrame = currentFrame;
      endFrame = totalFrames;
    }
    // Если текущая позиция вне диапазона — начинаем с начала диапазона
    if (currentFrame < startFrame || currentFrame >= endFrame) {
      currentFrame = startFrame;
    }

    android.util.Log.d("PLAYER", "play() startFrame=" + startFrame + " endFrame=" + endFrame
        + " totalFrames=" + totalFrames + " pcmLen=" + (pcm == null ? 0 : pcm.length));

    // Старый поток должен быть уже завершён (pause()/stop() ждут). Не блокируем UI надолго.
    if (playerThread != null && playerThread.isAlive()) {
      try {
        playerThread.join(500);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }
    playerThread = null;

    playing = true;
    playStartFrame = currentFrame;
    playStartTimeMs = System.currentTimeMillis();

    playerThread = new Thread(() -> {
      int minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
          AudioFormat.ENCODING_PCM_FLOAT);
      int bufSize = Math.max(minBuf, 8192);

      AudioTrack t = new AudioTrack.Builder()
          .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
          .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
              .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
          .setBufferSizeInBytes(bufSize * 4).setTransferMode(AudioTrack.MODE_STREAM).build();

      track = t;

      try {
        t.play();
        int chunk = bufSize / 2; // фреймов за раз
        int frame = startFrame;
        while (frame < endFrame) {
          if (!playing) {
            // Пауза — запоминаем точку и выходим
            break;
          }
          int n = Math.min(chunk, endFrame - frame);
          float[] block = new float[n * CHANNELS];
          System.arraycopy(pcm, frame * CHANNELS, block, 0, n * CHANNELS);

          int written = 0;
          int totalToWrite = block.length;
          boolean writeFailed = false;
          while (written < totalToWrite) {
            if (!playing)
              break;
            int w = t.write(block, written, totalToWrite - written, AudioTrack.WRITE_BLOCKING);
            if (w <= 0) {
              android.util.Log.w("PLAYER",
                  "write returned " + w + " playing=" + playing + " frame=" + frame);
              // underrun или ошибка — прерываем воспроизведение, а не зацикливаемся
              writeFailed = true;
              break;
            }
            written += w;
          }
          if (writeFailed || !playing)
            break;
          frame += n;
          currentFrame = frame;
        }

      } catch (Exception ignored) {
      } finally {
        playing = false;
        // currentFrame уже обновлялся в цикле записи (frame += n; currentFrame = frame),
        // поэтому он точно отражает, сколько фреймов ушло в трек. Не спрашиваем позицию у
        // уже остановленного/освобождённого AudioTrack — он может вернуть 0.
        int finalFrame = currentFrame;
        if (endFrame > 0 && finalFrame > endFrame)
          finalFrame = endFrame;

        if (t != null) {
          try {
            t.stop();
          } catch (Exception ignored) {
          }
          try {
            t.release();
          } catch (Exception ignored) {
          }
        }
        if (track == t)
          track = null;

        currentFrame = finalFrame;
        final boolean reachedEnd = (currentFrame >= endFrame && endFrame > 0);
        ui.post(() -> {
          if (listener != null) {
            listener.onStateChanged(false);
            listener.onPosition(currentFrame * 1000 / RATE);
            if (reachedEnd)
              listener.onEnded();
          }
        });
      }
    }, "AudioPlayer");
    playerThread.start();

    ui.removeCallbacks(positionTick);
    ui.post(positionTick);
    if (listener != null)
      listener.onStateChanged(true);
  }

  /** Пауза. Позиция сохраняется. Ждёт завершения потока плеера, чтобы не было гонки при повторном Play. */
  public void pause() {
    if (!playing)
      return;
    playing = false;
    ui.removeCallbacks(positionTick);
    if (playerThread != null) {
      try {
        playerThread.join(1000);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
      playerThread = null;
    }
    if (listener != null)
      listener.onStateChanged(false);
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
    if (listener != null)
      listener.onPosition(currentFrame * 1000 / RATE);
  }

  public void release() {
    stopInternal();
    this.listener = null;
    this.pcm = null;
  }

  public void setPlayRange(int startFrame, int endFrame) {
    this.startFrame = Math.max(0, startFrame);
    this.endFrame = Math.min(totalFrames, endFrame);
    if (this.endFrame <= this.startFrame) {
      this.endFrame = totalFrames;
    }
  }
}