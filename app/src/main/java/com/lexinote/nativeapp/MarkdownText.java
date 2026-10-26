package com.lexinote.nativeapp;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.widget.TextView;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight selectable Markdown spans. Model HTML is always treated as plain text. */
public final class MarkdownText {
  private MarkdownText() {}

  public static void apply(TextView view, String markdown) {
    SpannableStringBuilder out = new SpannableStringBuilder();
    boolean code = false;
    for (String line : markdown.split("\n", -1)) {
      if (line.trim().startsWith("```")) {
        code = !code;
        continue;
      }
      int start = out.length();
      if (code) {
        out.append(line).append('\n');
        span(out, new TypefaceSpan("monospace"), start, out.length());
        span(out, new RelativeSizeSpan(.9f), start, out.length());
        continue;
      }
      Matcher heading = Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(line);
      boolean isHeading = heading.matches();
      String text =
          isHeading
              ? heading.group(2)
              : line.replaceFirst("^\\s*[-*+]\\s+", "• ").replaceFirst("^>\\s?", "│ ");
      inline(out, text);
      if (isHeading) {
        span(out, new StyleSpan(Typeface.BOLD), start, out.length());
        span(
            out,
            new RelativeSizeSpan(heading.group(1).length() <= 2 ? 1.3f : 1.1f),
            start,
            out.length());
      }
      out.append('\n');
    }
    if (out.length() > 0) out.delete(out.length() - 1, out.length());
    view.setText(out);
    view.setTextIsSelectable(true);
  }

  private static void inline(SpannableStringBuilder out, String text) {
    Pattern token =
        Pattern.compile(
            "\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^]\\n]+)\\]\\((https://[^\\s)]+)\\)|\\*([^*]+)\\*");
    Matcher matcher = token.matcher(text);
    int end = 0;
    while (matcher.find()) {
      out.append(text, end, matcher.start());
      int start = out.length();
      if (matcher.group(1) != null) {
        out.append(matcher.group(1));
        span(out, new StyleSpan(Typeface.BOLD), start, out.length());
      } else if (matcher.group(2) != null) {
        out.append(matcher.group(2));
        span(out, new TypefaceSpan("monospace"), start, out.length());
      } else if (matcher.group(3) != null) {
        out.append(matcher.group(3)).append(" (").append(matcher.group(4)).append(')');
      } else {
        out.append(matcher.group(5));
        span(out, new StyleSpan(Typeface.ITALIC), start, out.length());
      }
      end = matcher.end();
    }
    out.append(text, end, text.length());
  }

  private static void span(SpannableStringBuilder target, Object span, int start, int end) {
    if (end > start) target.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
  }
}
