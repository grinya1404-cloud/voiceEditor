package com.grinya.voicesampleprep.util;

import com.grinya.voicesampleprep.model.AudioClip;
import com.grinya.voicesampleprep.model.ExportSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ExportUtils {

    public static List<int[]> buildRanges(AudioClip clip, boolean byMarkers, int selStart, int selEnd) {
        List<int[]> ranges = new ArrayList<>();
        if (byMarkers) {
            if (clip.markers.size() < 2) return ranges;
            List<Integer> all = new ArrayList<>(clip.markers);
            java.util.TreeSet<Integer> ts = new java.util.TreeSet<>(all);
            Integer[] arr = ts.toArray(new Integer[0]);
            for (int i = 0; i < arr.length - 1; i++) {
                if (arr[i + 1] > arr[i]) ranges.add(new int[]{arr[i], arr[i + 1]});
            }
        } else {
            if (selStart >= 0 && selEnd > selStart) {
                ranges.add(new int[]{selStart, selEnd});
            } else {
                ranges.add(new int[]{0, clip.frameCount()});
            }
        }
        return ranges;
    }

    public static String buildName(String template, String base, int index) {
        String out = template
                .replace("{name}", base)
                .replace("{index}", String.format(Locale.US, "%03d", index));
        return sanitize(out);
    }

    public static String stripExt(String s) {
        if (s == null) return "clip";
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    public static String sanitize(String s) {
        if (s == null || s.trim().isEmpty()) return "clip";
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}