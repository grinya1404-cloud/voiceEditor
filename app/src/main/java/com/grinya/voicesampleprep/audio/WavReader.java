package com.grinya.voicesampleprep.audio;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Минимальный WAV-ридер: PCM 8/16/24/32-bit int, 32-bit float. Возвращает всегда interleaved stereo float[] (L,R,L,R...). Моно дублируется в оба канала.
 */
public final class WavReader {

  public static class Result {
    public float[] pcm;
    public int sampleRate;
    public int channels;
    public int bits; // <-- НОВОЕ ПОЛЕ
  }

  private WavReader() {
  }

  public static Result read(File f) throws IOException {
    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
      if (raf.length() < 44)
        throw new IOException("Too short WAV");

      byte[] hdr = new byte[12];
      raf.readFully(hdr);
      if (!(hdr[0] == 'R' && hdr[1] == 'I' && hdr[2] == 'F' && hdr[3] == 'F')
          || !(hdr[8] == 'W' && hdr[9] == 'A' && hdr[10] == 'V' && hdr[11] == 'E')) {
        throw new IOException("Not a WAV file");
      }

      int format = 0; // 1=PCM, 3=IEEE float
      int channels = 0;
      int sampleRate = 0;
      int bitsPerSample = 0;
      long dataOffset = -1;
      long dataSize = -1;

      // читаем чанки
      while (raf.getFilePointer() + 8 <= raf.length()) {
        byte[] ch = new byte[8];
        raf.readFully(ch);
        String id = new String(ch, 0, 4, "US-ASCII");
        int size = le32(ch, 4);
        long next = raf.getFilePointer() + (size & 0xFFFFFFFFL);

        if ("fmt ".equals(id)) {
          byte[] fmt = new byte[Math.min(size, 16)];
          raf.readFully(fmt);
          format = le16(fmt, 0);
          channels = le16(fmt, 2);
          sampleRate = le32(fmt, 4);
          bitsPerSample = le16(fmt, 14);
          // пропускаем возможный хвост fmt (extensible)
          raf.seek(next);
        } else if ("data".equals(id)) {
          dataOffset = raf.getFilePointer();
          dataSize = size & 0xFFFFFFFFL;
          if (dataOffset + dataSize > raf.length()) {
            dataSize = raf.length() - dataOffset;
          }
          break;
        } else {
          raf.seek(next);
        }
      }

      if (dataOffset < 0)
        throw new IOException("No data chunk");
      if (channels < 1 || channels > 2)
        throw new IOException("Unsupported channels: " + channels);
      if (format != 1 && format != 3)
        throw new IOException("Unsupported format: " + format);

      int bytesPerSample = bitsPerSample / 8;
      if (bytesPerSample < 1 || bytesPerSample > 4)
        throw new IOException("Unsupported bps: " + bitsPerSample);

      long frames = dataSize / (bytesPerSample * (long) channels);
      int frameCount = (int) Math.min(frames, Integer.MAX_VALUE / 4);

      float[] monoOrStereo = new float[frameCount * channels];
      byte[] buf = new byte[8192];
      int outIdx = 0;
      long remaining = (long) frameCount * channels * bytesPerSample;
      raf.seek(dataOffset);
      int carry = 0;
      byte[] carryBuf = new byte[4];

      while (remaining > 0) {
        int toRead = (int) Math.min(buf.length, remaining);
        int n = raf.read(buf, 0, toRead);
        if (n <= 0)
          break;
        remaining -= n;

        int i = 0;
        while (i < n) {
          // нужно bytesPerSample байт, но они могут быть разорваны между блоками
          int need = bytesPerSample;
          int have = n - i;
          if (carry + have < need) {
            System.arraycopy(buf, i, carryBuf, carry, have);
            carry += have;
            i = n;
            continue;
          }
          int takeFromBuf = Math.min(need - carry, have);
          System.arraycopy(buf, i, carryBuf, carry, takeFromBuf);
          carry += takeFromBuf;
          i += takeFromBuf;

          // теперь в carryBuf ровно need байт
          float v;
          if (format == 3 && bitsPerSample == 32) {
            int bits = ((carryBuf[3] & 0xFF) << 24) | ((carryBuf[2] & 0xFF) << 16)
                | ((carryBuf[1] & 0xFF) << 8) | (carryBuf[0] & 0xFF);
            v = Float.intBitsToFloat(bits);
          } else if (bitsPerSample == 8) {
            // unsigned 8-bit
            v = ((carryBuf[0] & 0xFF) - 128) / 128f;
          } else if (bitsPerSample == 16) {
            short s = (short) (((carryBuf[1] & 0xFF) << 8) | (carryBuf[0] & 0xFF));
            v = s / 32768f;
          } else if (bitsPerSample == 24) {
            int s = ((carryBuf[2] & 0xFF) << 16) | ((carryBuf[1] & 0xFF) << 8)
                | (carryBuf[0] & 0xFF);
            if ((s & 0x800000) != 0)
              s |= 0xFF000000;
            v = s / 8388608f;
          } else { // 32-bit int
            int s = ((carryBuf[3] & 0xFF) << 24) | ((carryBuf[2] & 0xFF) << 16)
                | ((carryBuf[1] & 0xFF) << 8) | (carryBuf[0] & 0xFF);
            v = (float) (s / 2147483648.0);
          }
          carry = 0;

          if (outIdx < monoOrStereo.length) {
            monoOrStereo[outIdx++] = v;
          }
        }
      }

      int actualFrames = outIdx / channels;
      Result r = new Result();
      r.sampleRate = sampleRate;
      r.channels = channels;
      r.bits = bitsPerSample; 

      if (channels == 2) {
        r.pcm = new float[actualFrames * 2];
        System.arraycopy(monoOrStereo, 0, r.pcm, 0, actualFrames * 2);
      } else {
        r.pcm = new float[actualFrames * 2];
        for (int i = 0; i < actualFrames; i++) {
          float s = monoOrStereo[i];
          r.pcm[i * 2] = s;
          r.pcm[i * 2 + 1] = s;
        }
      }
      return r;
    }
  }

  private static int le16(byte[] b, int off) {
    return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
  }

  private static int le32(byte[] b, int off) {
    return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16)
        | ((b[off + 3] & 0xFF) << 24);
  }
}