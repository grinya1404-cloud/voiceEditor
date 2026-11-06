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

  public AudioPlayer() {
    positionTick = new Runnable() {
      @Override
      public void run() {
        if (listener != null && playing) {
          // Позицию рассчитываем по времени, чтобы линия ехала плавно (60 fps)
          int elapsedMs = (int) (System.currentTimeMillis() - playStartTimeMs);
          int frame = playStartFrame + (elapsedMs * RATE / 1000);
          if (frame > endFrame)
            frame = endFrame;
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

  public void setPcm(float[] pcm) {
    pause();
    this.pcm = pcm;
    this.totalFrames = (pcm == null) ? 0 : pcm.length / CHANNELS;
    this.currentFrame = 0;
    this.endFrame = totalFrames;
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
    boolean wasPlaying = playing;
    pause();
    currentFrame = Math.max(0, Math.min(endFrame, frame));
    notifyPosition();
    if (wasPlaying)
      play();
  }

  public void play() {
    if (pcm == null || totalFrames == 0)
      return;
    if (playing)
      return;

    if (currentFrame >= endFrame)
      currentFrame = 0; // Если в конце — начинаем сначала
    playStartFrame = currentFrame;
    playStartTimeMs = System.currentTimeMillis();
    playing = true;

    playerThread = new Thread(() -> {
      // Буфер делаем небольшим (~85 мс). Это даст минимальную задержку при остановке.
      int minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
          AudioFormat.ENCODING_PCM_FLOAT);
      int bufSize = Math.max(minBuf, 65536);

      AudioTrack t = new AudioTrack.Builder()
          .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
          .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
              .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
          .setBufferSizeInBytes(bufSize * 4).setTransferMode(AudioTrack.MODE_STREAM).build();

      track = t;
      t.play();

      int chunk = bufSize / 2; // Количество фреймов за один блок
      int frame = playStartFrame;

      try {
        while (frame < endFrame && playing) {
          int n = Math.min(chunk, endFrame - frame);
          float[] block = new float[n * CHANNELS];
          System.arraycopy(pcm, frame * CHANNELS, block, 0, n * CHANNELS);

          int written = 0;
          while (written < block.length && playing) {
            int w = t.write(block, written, block.length - written, AudioTrack.WRITE_BLOCKING);
            if (w < 0) {
              // Фатальная ошибка записи (трек умер)
              android.util.Log.e("PLAYER", "write error: " + w);
              break;
            }
            written += w;
          }
          frame += n;
          // Дописываем 200 мс тишины, чтобы Bluetooth/наушники гарантированно
          // воспроизвели последние фреймы перед закрытием AudioTrack
          if (playing) {
            int silenceFrames = RATE / 5; // 200 мс
            float[] silence = new float[silenceFrames * CHANNELS];
            int silenceWritten = 0; // ← переименовали written в silenceWritten
            while (silenceWritten < silence.length && playing) {
              int w = t.write(silence, silenceWritten, silence.length - silenceWritten,
                  AudioTrack.WRITE_BLOCKING);
              if (w < 0)
                break;
              silenceWritten += w;
            }
          }
        }
      } catch (Exception ignored) {
      } finally {
        playing = false;

        if (t.getState() == AudioTrack.STATE_INITIALIZED) {
          // времянка кдалить
          android.util.Log.d("PLAYER", "Playback finished. Total frames sent: " + frame);
          try {
            // Даем AudioTrack 250 мс, чтобы он выплюнул остатки буфера в динамик
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
        }
        if (track == t)
          track = null;

        currentFrame = Math.min(currentFrame, endFrame);

        ui.post(() -> {
          if (listener != null) {
            listener.onStateChanged(false);
            listener.onPosition(currentFrame * 1000 / RATE);
            // Если доиграли до конца, шлем onEnded
            if (currentFrame >= endFrame && endFrame > 0) {
              listener.onEnded();
            }
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
    if (!playing)
      return;
    playing = false;
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