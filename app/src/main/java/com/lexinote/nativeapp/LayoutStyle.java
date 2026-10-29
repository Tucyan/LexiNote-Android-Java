package com.lexinote.nativeapp;

/** Density, responsive sizing and color rules shared by the native pages. */
final class LayoutStyle {
  static final int TOUCH_MIN = 48;
  static final int HOME_BASE = 272;
  private LayoutStyle() {}

  static boolean compactHeader(int widthDp, float appScale) {
    return widthDp < 400 || appScale > 1.1f;
  }

  static int modeHeight(int widthDp, float appScale) {
    return widthDp < 360 && appScale > 1.1f ? 56 : 48;
  }

  static int cardPadding(boolean compact) { return compact ? 12 : 16; }
  static int cardGap(boolean compact) { return compact ? 10 : 16; }

  static double luminance(int color) {
    return .2126 * linear((color >> 16) & 255)
        + .7152 * linear((color >> 8) & 255) + .0722 * linear(color & 255);
  }

  private static double linear(int value) {
    double v = value / 255.0;
    return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);
  }

  static double contrast(int first, int second) {
    double a = luminance(first), b = luminance(second);
    return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
  }

  static int onColor(int fill) {
    return contrast(fill, 0xff000000) >= contrast(fill, 0xffffffff)
        ? 0xff000000 : 0xffffffff;
  }

  static int textAccent(int saved, int surface) { return adjusted(saved, surface, 5.0); }
  static int fillAccent(int saved) { return adjusted(saved, 0xffffffff, 4.5); }

  private static int adjusted(int color, int surface, double target) {
    color |= 0xff000000;
    if (contrast(color, surface) >= target) return color;
    int destination = luminance(surface) < .18 ? 255 : 0;
    for (int step = 1; step <= 100; step++) {
      double amount = step / 100.0;
      int r = mix((color >> 16) & 255, destination, amount);
      int g = mix((color >> 8) & 255, destination, amount);
      int b = mix(color & 255, destination, amount);
      int candidate = 0xff000000 | (r << 16) | (g << 8) | b;
      if (contrast(candidate, surface) >= target) return candidate;
    }
    return onColor(surface);
  }

  private static int mix(int value, int destination, double amount) {
    return (int) Math.round(value * (1 - amount) + destination * amount);
  }
}
