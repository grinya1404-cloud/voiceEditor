package com.grinya.voicesampleprep.util;

public final class FileNames {
  private FileNames() {
  }

  public static String sanitize(String name) {
    if (name == null || name.trim().isEmpty())
      name = "untitled";
    String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
    while (s.endsWith("."))
      s = s.substring(0, s.length() - 1);
    if (s.length() > 120)
      s = s.substring(0, 120);
    return s.isEmpty() ? "untitled" : s;
  }
}