package com.grinya.voicesampleprep.model;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class MarkerStore {

  public static void save(File wavFile, List<Integer> markers) {
    if (wavFile == null)
      return;
    File mFile = new File(wavFile.getAbsolutePath() + ".markers");
    try (FileWriter fw = new FileWriter(mFile)) {
      fw.write("[\n");
      for (int i = 0; i < markers.size(); i++) {
        fw.write("  " + markers.get(i));
        if (i < markers.size() - 1)
          fw.write(",");
        fw.write("\n");
      }
      fw.write("]");
    } catch (IOException e) {
      Log.e("MarkerStore", "Save failed", e);
    }
  }

  public static List<Integer> load(File wavFile) {
    List<Integer> markers = new ArrayList<>();
    if (wavFile == null)
      return markers;
    File mFile = new File(wavFile.getAbsolutePath() + ".markers");
    if (!mFile.exists())
      return markers;

    try (Scanner sc = new Scanner(mFile)) {
      StringBuilder sb = new StringBuilder();
      while (sc.hasNextLine())
        sb.append(sc.nextLine());
      String json = sb.toString().trim();
      if (json.startsWith("[") && json.endsWith("]")) {
        json = json.substring(1, json.length() - 1).trim();
        if (!json.isEmpty()) {
          String[] parts = json.split(",");
          for (String p : parts) {
            if (!p.trim().isEmpty()) {
              markers.add(Integer.parseInt(p.trim()));
            }
          }
        }
      }
    } catch (Exception e) {
      Log.e("MarkerStore", "Load failed", e);
    }
    return markers;
  }
}