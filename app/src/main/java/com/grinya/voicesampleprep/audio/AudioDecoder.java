package com.grinya.voicesampleprep.audio;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public final class AudioDecoder {

  public static final int TARGET_RATE = 48000;

  public static class Result {
    public float[] pcm; // уже 48к стерео
    public int sampleRate; // = 48000
    public int channels; // = 2

    // НОВЫЕ ПОЛЯ:
    public int originalSampleRate;
    public int originalChannels;
    public int originalBits; // 0, если неизвестно
    public String originalFormat; // "wav", "mp3", "m4a", "flac"
  }

  private AudioDecoder() {
  }

  public static Result decode(File f) throws IOException {
    String name = f.getName().toLowerCase();
    if (name.endsWith(".wav")) {
      WavReader.Result w = WavReader.read(f);
      float[] pcm = w.pcm;
      if (w.sampleRate != TARGET_RATE) {
        pcm = com.grinya.voicesampleprep.util.AudioOps.resample(pcm, w.sampleRate, TARGET_RATE);
      }
      Result r = new Result();
      r.pcm = pcm;
      r.sampleRate = TARGET_RATE;
      r.originalSampleRate = w.sampleRate;
      r.originalChannels = w.channels;
      r.originalBits = w.bits;
      r.originalFormat = "wav";
      return r;
    }
    return decodeViaCodec(f);
  }

  private static Result decodeViaCodec(File f) throws IOException {
    MediaExtractor ex = new MediaExtractor();
    String ext = "unknown";
    String fileName = f.getName().toLowerCase();
    int dotIdx = fileName.lastIndexOf('.');
    if (dotIdx > 0) {
      ext = fileName.substring(dotIdx + 1);
    }

    try {
      ex.setDataSource(f.getAbsolutePath());
      int track = -1;
      MediaFormat fmt = null;
      for (int i = 0; i < ex.getTrackCount(); i++) {
        MediaFormat mf = ex.getTrackFormat(i);
        String mime = mf.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) {
          track = i;
          fmt = mf;
          break;
        }
      }
      if (track < 0)
        throw new IOException("No audio track");
      ex.selectTrack(track);

      String mime = fmt.getString(MediaFormat.KEY_MIME);
      MediaCodec codec = MediaCodec.createDecoderByType(mime);
      codec.configure(fmt, null, null, 0);
      codec.start();

      // соберём всё в список short-массивов, потом объединим
      java.util.ArrayList<short[]> chunks = new java.util.ArrayList<>();
      long totalShorts = 0;

      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      boolean inputDone = false;
      boolean outputDone = false;

      while (!outputDone) {
        if (!inputDone) {
          int inIdx = codec.dequeueInputBuffer(10000);
          if (inIdx >= 0) {
            ByteBuffer inBuf = codec.getInputBuffer(inIdx);
            int sz = ex.readSampleData(inBuf, 0);
            if (sz < 0) {
              codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputDone = true;
            } else {
              codec.queueInputBuffer(inIdx, 0, sz, ex.getSampleTime(), 0);
              ex.advance();
            }
          }
        }

        int outIdx = codec.dequeueOutputBuffer(info, 10000);
        if (outIdx >= 0) {
          ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
          if (outBuf != null && info.size > 0) {
            outBuf.position(info.offset);
            outBuf.limit(info.offset + info.size);
            ShortBuffer sb = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer();
            short[] arr = new short[sb.remaining()];
            sb.get(arr);
            chunks.add(arr);
            totalShorts += arr.length;
          }
          codec.releaseOutputBuffer(outIdx, false);
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
            outputDone = true;
        } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          // игнорируем, читаем каналы из исходного fmt
        }
      }

      codec.stop();
      codec.release();

      // сколько каналов в исходнике?
      int srcCh = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
          ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
          : 1;
      int srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
          ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
          : 44100;

      int totalFrames = (int) (totalShorts / Math.max(1, srcCh));
      float[] stereo = new float[totalFrames * 2];
      int frame = 0;
      int carry = 0;
      for (short[] arr : chunks) {
        int i = 0;
        while (i < arr.length && frame < totalFrames) {
          if (srcCh == 1) {
            float v = arr[i] / 32768f;
            stereo[frame * 2] = v;
            stereo[frame * 2 + 1] = v;
            i += 1;
            frame++;
          } else {
            if (i + 1 >= arr.length) {
              carry = 1;
              break;
            }
            stereo[frame * 2] = arr[i] / 32768f;
            stereo[frame * 2 + 1] = arr[i + 1] / 32768f;
            i += 2;
            frame++;
          }
        }
      }

      if (srcRate != TARGET_RATE) {
        stereo = com.grinya.voicesampleprep.util.AudioOps.resample(stereo, srcRate, TARGET_RATE);
      }

      Result r = new Result();
      r.pcm = stereo;
      r.sampleRate = TARGET_RATE;
      r.originalSampleRate = srcRate; // <--
      r.originalChannels = srcCh; // <--
      r.originalBits = 0; // MP3 не имеет фиксированной битности
      r.originalFormat = ext; // "mp3", "m4a", "flac" (из имени файла)
      return r;

    } finally {
      ex.release();
    }
  }
}