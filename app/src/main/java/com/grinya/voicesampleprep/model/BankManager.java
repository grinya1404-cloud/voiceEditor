package com.grinya.voicesampleprep.model;

import android.content.Context;

import com.grinya.voicesampleprep.audio.AudioDecoder;
import com.grinya.voicesampleprep.audio.WavReader;
import com.grinya.voicesampleprep.audio.WavWriter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BankManager {

    private final Context appContext;
    private final File banksRoot;

    public BankManager(Context c) {
        this.appContext = c.getApplicationContext();
        this.banksRoot = new File(appContext.getExternalFilesDir(null), "banks");
        if (!banksRoot.exists()) banksRoot.mkdirs();
    }

    public File getBanksRoot() { return banksRoot; }

    /** Список банков (папок первого уровня). */
    public List<Bank> listBanks() {
        List<Bank> result = new ArrayList<>();
        File[] dirs = banksRoot.listFiles(File::isDirectory);
        if (dirs == null) return result;
        Arrays.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File d : dirs) {
            Bank b = new Bank();
            b.name = d.getName();
            b.dir = d;
            File[] files = d.listFiles((dir, n) -> n.toLowerCase().endsWith(".wav"));
            if (files != null) {
                Arrays.sort(files, (a, c) -> a.getName().compareToIgnoreCase(c.getName()));
                for (File f : files) {
                    AudioClip clip = new AudioClip(f.getName(), new float[0], AudioClip.SAMPLE_RATE);
                    clip.dir = f;
                    b.clips.add(clip);
                }
            }
            result.add(b);
        }
        return result;
    }

    public Bank createBank(String name) {
        String safe = sanitize(name);
        File dir = new File(banksRoot, safe);
        if (!dir.exists()) dir.mkdirs();
        Bank b = new Bank();
        b.name = safe;
        b.dir = dir;
        return b;
    }

    public Bank deleteBank(Bank b) {
        if (b.dir != null) deleteRecursive(b.dir);
        return b;
    }

    /** Сохранить клип в банк как WAV (16-bit, 48к, stereo). Возвращает файл. */
    public File saveClipToBank(Bank bank, AudioClip clip, String baseName) throws IOException {
        String safe = sanitize(baseName);
        File f = new File(bank.dir, safe + ".wav");
        int counter = 1;
        while (f.exists()) {
            f = new File(bank.dir, safe + "_" + counter + ".wav");
            counter++;
        }
        WavWriter.write(f, clip.pcm, clip.sampleRate, clip.sampleRate, 16, false);
        return f;
    }

    /** Загрузить PCM из файла в AudioClip. */
    public AudioClip loadClip(File wavFile) throws IOException {
        WavReader.Result r = WavReader.read(wavFile);
        AudioClip c = new AudioClip(wavFile.getName(), r.pcm, r.sampleRate);
        c.dir = wavFile;
        return c;
    }

    /** Импортировать любой файл → сохранить как WAV в банк → вернуть клип. */
    public AudioClip importFile(Bank bank, File src) throws IOException {
        AudioDecoder.Result r = AudioDecoder.decode(src);
        String base = src.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String safe = sanitize(base);
        File out = new File(bank.dir, safe + ".wav");
        int counter = 1;
        while (out.exists()) {
            out = new File(bank.dir, safe + "_" + counter + ".wav");
            counter++;
        }
        WavWriter.write(out, r.pcm, r.sampleRate, r.sampleRate, 16, false);
        AudioClip c = new AudioClip(out.getName(), r.pcm, r.sampleRate);
        c.dir = out;
        return c;
    }

    private static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) name = "untitled";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}