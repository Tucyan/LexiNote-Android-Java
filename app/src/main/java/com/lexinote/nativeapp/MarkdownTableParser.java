package com.lexinote.nativeapp;

import java.util.ArrayList;
import java.util.List;

/** Small pure Markdown table parser; fenced code is deliberately left to the block parser. */
final class MarkdownTableParser {
  private MarkdownTableParser() {}

  static boolean isSeparator(String line) {
    List<String> cells = split(line);
    if (cells.isEmpty()) return false;
    for (String cell : cells) if (!cell.trim().matches(":?-{3,}:?")) return false;
    return true;
  }

  static List<String> split(String line) {
    String s = line == null ? "" : line.trim();
    if (s.startsWith("|")) s = s.substring(1);
    if (s.endsWith("|") && !isEscaped(s, s.length() - 1)) s = s.substring(0, s.length() - 1);
    List<String> cells = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean code = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '`' && !isEscaped(s, i)) code = !code;
      if (c == '|' && !code && !isEscaped(s, i)) {
        cells.add(unescape(cell.toString()).trim());
        cell.setLength(0);
      } else cell.append(c);
    }
    cells.add(unescape(cell.toString()).trim());
    return cells;
  }

  private static boolean isEscaped(String s, int i) {
    int slashes = 0;
    for (int p = i - 1; p >= 0 && s.charAt(p) == '\\'; p--) slashes++;
    return (slashes & 1) != 0;
  }

  private static String unescape(String s) {
    return s.replace("\\|", "|");
  }
}
