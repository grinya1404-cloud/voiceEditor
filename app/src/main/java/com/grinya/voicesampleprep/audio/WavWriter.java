package com.grinya.voicesampleprep.audio;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import com.grinya.voicesampleprep.util.AudioOps;   // ← добавь эту строку

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class WavWriter {

    /**
     * @param pcm        interleaved stereo (L,R,L,R...)
     * @param srcRate    sample rate исходного pcm
     * @param dstRate    sample rate для файла (если != srcRate — пересэмплируем)
     * @param bits       16 или 24
     * @param mono       true — свести в моно
     */
    public static void write(File f, float[] pcm, int srcRate, int dstRate, int bits, boolean mono)
            throws IOException {

        float[] data = pcm;
        int rate = srcRate;

        if (dstRate != srcRate) {
            data = AudioOps.resample(data, srcRate, dstRate);
            rate = dstRate;
        }

        if (mono) {
            data = AudioOps.toMono(data);
        }

        int channels = mono ? 1 : 2;
        int bytesPerSample = bits / 8;
        int dataBytes = data.length * bytesPerSample;

        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(f), 65536)) {
            writeAscii(out, "RIFF");
            writeLE32(out, 36 + dataBytes);
            writeAscii(out, "WAVE");

            writeAscii(out, "fmt ");
            writeLE32(out, 16);
            writeLE16(out, 1);           // PCM
            writeLE16(out, channels);
            writeLE32(out, rate);
            writeLE32(out, rate * channels * bytesPerSample); // byte rate
            writeLE16(out, channels * bytesPerSample);        // block align
            writeLE16(out, bits);

            writeAscii(out, "data");
            writeLE32(out, dataBytes);

            if (bits == 16) {
                byte[] buf = new byte[8192];
                int bi = 0;
                for (float v : data) {
                    int s = Math.round(v * 32767f);
                    if (s > 32767) s = 32767;
                    if (s < -32768) s = -32768;
                    buf[bi++] = (byte) (s & 0xFF);
                    buf[bi++] = (byte) ((s >> 8) & 0xFF);
                    if (bi == buf.length) { out.write(buf, 0, bi); bi = 0; }
                }
                if (bi > 0) out.write(buf, 0, bi);
            } else { // 24
                byte[] buf = new byte[8192];
                int bi = 0;
                for (float v : data) {
                    int s = Math.round(v * 8388607f);
                    if (s > 8388607) s = 8388607;
                    if (s < -8388608) s = -8388608;
                    buf[bi++] = (byte) (s & 0xFF);
                    buf[bi++] = (byte) ((s >> 8) & 0xFF);
                    buf[bi++] = (byte) ((s >> 16) & 0xFF);
                    if (bi == buf.length) { out.write(buf, 0, bi); bi = 0; }
                }
                if (bi > 0) out.write(buf, 0, bi);
            }
        }
    }

    private static void writeAscii(BufferedOutputStream out, String s) throws IOException {
        out.write(s.getBytes("US-ASCII"));
    }

    private static void writeLE16(BufferedOutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeLE32(BufferedOutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }
}