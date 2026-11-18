package com.grinya.voicesampleprep.model;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class AudioMeta {

  public int sampleRate;
  public int channels;
  public int bits; // 0 = неизвестно (MP3, FLAC)
  public String format; // "wav", "mp3" и т.д.

  public List<Integer> markers = new ArrayList<>();
  public List<int[]> exportIntervals = new ArrayList<>();

  // ---------- Сохранение ----------

  /** Атомарная запись: пишем во временный .meta.tmp, затем rename. */
  public static void save(File wavFile, AudioMeta meta) {
    if (wavFile == null)
      return;
    File metaFile = new File(wavFile.getAbsolutePath() + ".meta");
    File tmpFile = new File(metaFile.getAbsolutePath() + ".tmp");
    try (FileWriter fw = new FileWriter(tmpFile)) {
      fw.write("{\n");
      fw.write("  \"markers\": " + markersToJson(meta.markers) + ",\n");
      fw.write("  \"exportIntervals\": " + intervalsToJson(meta.exportIntervals) + ",\n");
      fw.write("  \"original\": {\n");
      fw.write("    \"sampleRate\": " + meta.sampleRate + ",\n");
      fw.write("    \"channels\": " + meta.channels + ",\n");
      fw.write("    \"bits\": " + meta.bits + ",\n");
      fw.write("    \"format\": \"" + escape(meta.format) + "\"\n");
      fw.write("  }\n");
      fw.write("}\n");
    } catch (IOException e) {
      Log.e("AudioMeta", "Save failed", e);
      //noinspection ResultOfMethodCallIgnored
      tmpFile.delete();
      return;
    }
    //noinspection ResultOfMethodCallIgnored
    metaFile.delete();
    if (!tmpFile.renameTo(metaFile)) {
      Log.e("AudioMeta", "Rename failed: " + tmpFile + " -> " + metaFile);
      //noinspection ResultOfMethodCallIgnored
      tmpFile.delete();
    }
  }

  // ---------- Загрузка ----------

  public static AudioMeta load(File wavFile) {
    AudioMeta meta = new AudioMeta();
    if (wavFile == null)
      return meta;
    File metaFile = new File(wavFile.getAbsolutePath() + ".meta");
    if (!metaFile.exists())
      return meta;

    try (Scanner sc = new Scanner(metaFile)) {
      StringBuilder sb = new StringBuilder();
      while (sc.hasNextLine())
        sb.append(sc.nextLine());
      String json = sb.toString().trim();

      int mkStart = json.indexOf("\"markers\"");
      if (mkStart >= 0) {
        int arrStart = json.indexOf("[", mkStart);
        int arrEnd = json.indexOf("]", arrStart);
        if (arrStart >= 0 && arrEnd > arrStart) {
          String arr = json.substring(arrStart + 1, arrEnd).trim();
          if (!arr.isEmpty()) {
            for (String s : arr.split(",")) {
              try {
                meta.markers.add(Integer.parseInt(s.trim()));
              } catch (Exception ignored) {
              }
            }
          }
        }
      }
      int eiStart = json.indexOf("\"exportIntervals\"");
      if (eiStart >= 0) {
        int arrStart = json.indexOf("[", eiStart);
        if (arrStart >= 0) {
          int depth = 0;
          int arrEnd = -1;
          for (int i = arrStart; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '[')
              depth++;
            else if (ch == ']') {
              depth--;
              if (depth == 0) {
                arrEnd = i;
                break;
              }
            }
          }
          if (arrEnd > arrStart) {
            String arr = json.substring(arrStart, arrEnd + 1);
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\[(\\d+)\\s*,\\s*(\\d+)\\]").matcher(arr);
            while (m.find()) {
              try {
                int a = Integer.parseInt(m.group(1));
                int b = Integer.parseInt(m.group(2));
                meta.exportIntervals.add(new int[] { a, b });
              } catch (Exception ignored) {
              }
            }
          }
        }
      }
      meta.sampleRate = parseIntField(json, "sampleRate");
      meta.channels = parseIntField(json, "channels");
      meta.bits = parseIntField(json, "bits");
      meta.format = parseStringField(json, "format");

    } catch (Exception e) {
      Log.e("AudioMeta", "Load failed", e);
    }
    return meta;
  }

  // ---------- Хелперы ----------

  private static String markersToJson(List<Integer> markers) {
    if (markers == null || markers.isEmpty())
      return "[]";
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < markers.size(); i++) {
      sb.append(markers.get(i));
      if (i < markers.size() - 1)
        sb.append(", ");
    }
    sb.append("]");
    return sb.toString();
  }

  private static String intervalsToJson(List<int[]> intervals) {
    if (intervals == null || intervals.isEmpty())
      return "[]";
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < intervals.size(); i++) {
      sb.append("[").append(intervals.get(i)[0]).append(", ").append(intervals.get(i)[1])
          .append("]");
      if (i < intervals.size() - 1)
        sb.append(", ");
    }
    sb.append("]");
    return sb.toString();
  }

  private static int parseIntField(String json, String key) {
    int idx = json.indexOf("\"" + key + "\"");
    if (idx < 0)
      return 0;
    int colon = json.indexOf(":", idx);
    if (colon < 0)
      return 0;
    int end = colon + 1;
    while (end < json.length()) {
      char c = json.charAt(end);
      if (c == ',' || c == '}' || c == '\n')
        break;
      end++;
    }
    String val = json.substring(colon + 1, end).trim();
    try {
      return Integer.parseInt(val);
    } catch (Exception e) {
      return 0;
    }
  }

  private static String parseStringField(String json, String key) {
    int idx = json.indexOf("\"" + key + "\"");
    if (idx < 0)
      return "";
    int colon = json.indexOf(":", idx);
    if (colon < 0)
      return "";
    int q1 = json.indexOf("\"", colon + 1);
    if (q1 < 0)
      return "";
    int q2 = json.indexOf("\"", q1 + 1);
    if (q2 < 0)
      return "";
    return json.substring(q1 + 1, q2);
  }

  private static String escape(String s) {
    if (s == null)
      return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        .replace("\r", "\\r");
  }
}