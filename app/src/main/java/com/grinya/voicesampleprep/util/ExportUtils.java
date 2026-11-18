package com.grinya.voicesampleprep.util;

import com.grinya.voicesampleprep.model.AudioClip;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ExportUtils {

  public static List<int[]> buildRanges(AudioClip clip, boolean byMarkers, int selStart,
      int selEnd) {
    List<int[]> ranges = new ArrayList<>();
    if (byMarkers) {
      if (clip.exportIntervals != null && !clip.exportIntervals.isEmpty()) {
        for (int[] i : clip.exportIntervals) {
          ranges.add(new int[] { i[0], i[1] });
        }
        return ranges;
      }

      if (clip.markers.size() < 2)
        return ranges;
      // D9: markers уже отсортированы (Collections.sort в addMarker)
      for (int i = 0; i < clip.markers.size() - 1; i++) {
        int a = clip.markers.get(i);
        int b = clip.markers.get(i + 1);
        if (b > a)
          ranges.add(new int[] { a, b });
      }
    } else {
      if (selStart >= 0 && selEnd > selStart) {
        ranges.add(new int[] { selStart, selEnd });
      } else {
        ranges.add(new int[] { 0, clip.frameCount() });
      }
    }
    return ranges;
  }

  public static String buildName(String template, String base, int index) {
    String out = template.replace("{name}", base).replace("{index}",
        String.format(Locale.US, "%03d", index));
    return sanitize(out);
  }

  public static String stripExt(String s) {
    if (s == null)
      return "clip";
    int dot = s.lastIndexOf('.');
    return dot > 0 ? s.substring(0, dot) : s;
  }

  /** D8: единый sanitize. */
  public static String sanitize(String s) {
    return FileNames.sanitize(s);
  }
}