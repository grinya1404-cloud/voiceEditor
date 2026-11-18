package com.grinya.voicesampleprep.model;

import android.content.Context;

import com.grinya.voicesampleprep.audio.AudioDecoder;
import com.grinya.voicesampleprep.audio.WavReader;
import com.grinya.voicesampleprep.audio.WavWriter;
import com.grinya.voicesampleprep.util.FileNames;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BankManager {

  private final File banksRoot;

  public BankManager(Context c) {
    File external = c.getApplicationContext().getExternalFilesDir(null);
    this.banksRoot = new File(external, "banks");
    if (!banksRoot.exists())
      banksRoot.mkdirs();
  }

  public File getBanksRoot() {
    return banksRoot;
  }

  /** Список банков (папок первого уровня). E1: читает .meta для SR/каналов/битности. */
  public List<Bank> listBanks() {
    List<Bank> result = new ArrayList<>();
    File[] dirs = banksRoot.listFiles(File::isDirectory);
    if (dirs == null)
      return result;
    Arrays.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    for (File d : dirs) {
      Bank b = new Bank();
      b.name = d.getName();
      b.dir = d;
      File[] files = d.listFiles((dir, n) -> n.toLowerCase().endsWith(".wav"));
      if (files != null) {
        Arrays.sort(files, (a, c) -> a.getName().compareToIgnoreCase(c.getName()));
        for (File f : files) {
          AudioMeta meta = AudioMeta.load(f);
          int sr = meta.sampleRate > 0 ? meta.sampleRate : AudioClip.SAMPLE_RATE;
          AudioClip clip = new AudioClip(f.getName(), new float[0], sr);
          clip.dir = f;
          b.clips.add(clip);
        }
      }
      result.add(b);
    }
    return result;
  }

  /**
   * E2: если банк с таким именем уже есть — возвращаем его (не создаём дубль).
   * Если имя пустое/невалидное — sanitize → "untitled".
   */
  public Bank createBank(String name) {
    String safe = sanitize(name);
    File dir = new File(banksRoot, safe);
    if (!dir.exists())
      dir.mkdirs();
    Bank b = new Bank();
    b.name = safe;
    b.dir = dir;
    return b;
  }

  /** E3: возвращает true, если папка удалена (или уже не существует). */
  public boolean deleteBank(Bank b) {
    if (b == null || b.dir == null)
      return false;
    if (!b.dir.exists())
      return true;
    return deleteRecursive(b.dir);
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
    WavWriter.write(f, clip.pcm, clip.sampleRate, clip.sampleRate, 16, false, false, false, false,
        false);
    return f;
  }

  /** Загрузить PCM из файла в AudioClip. */
  public AudioClip loadClip(File wavFile) throws IOException {
    WavReader.Result r = WavReader.read(wavFile);
    AudioClip c = new AudioClip(wavFile.getName(), r.pcm, r.sampleRate);
    c.dir = wavFile;

    AudioMeta meta = AudioMeta.load(wavFile);
    c.markers = meta.markers;
    c.exportIntervals = meta.exportIntervals;

    return c;
  }

  /** Импортировать любой файл → сохранить как WAV в банк → вернуть клип. */
  public AudioClip importFile(Bank bank, File src) throws IOException {
    AudioDecoder.Result r = AudioDecoder.decode(src);
    String base = src.getName();
    int dot = base.lastIndexOf('.');
    if (dot > 0)
      base = base.substring(0, dot);
    String safe = sanitize(base);
    File out = new File(bank.dir, safe + ".wav");
    int counter = 1;
    while (out.exists()) {
      out = new File(bank.dir, safe + "_" + counter + ".wav");
      counter++;
    }
    WavWriter.write(out, r.pcm, r.sampleRate, r.sampleRate, 16, false, false, false, false, false);

    // Сохраняем .meta с параметрами ИСХОДНИКА (до конвертации)
    AudioMeta meta = new AudioMeta();
    meta.sampleRate = r.originalSampleRate;
    meta.channels = r.originalChannels;
    meta.bits = r.originalBits;
    meta.format = r.originalFormat;
    AudioMeta.save(out, meta);

    AudioClip c = new AudioClip(out.getName(), r.pcm, r.sampleRate);
    c.dir = out;
    return c;
  }

  private static String sanitize(String name) {
    return FileNames.sanitize(name);
  }

  /** E3: рекурсивное удаление с проверкой результата. */
  private static boolean deleteRecursive(File f) {
    boolean ok = true;
    if (f.isDirectory()) {
      File[] kids = f.listFiles();
      if (kids != null) {
        for (File k : kids)
          ok &= deleteRecursive(k);
      }
    }
    ok &= f.delete();
    return ok;
  }
}