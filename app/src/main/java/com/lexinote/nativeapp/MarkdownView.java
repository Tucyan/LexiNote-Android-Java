package com.lexinote.nativeapp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Native Markdown container with horizontally scrollable code blocks and tables. */
final class MarkdownView extends LinearLayout {
  private final int foreground;
  private final int border;

  MarkdownView(Context context, int foreground, int border) {
    super(context);
    this.foreground = foreground;
    this.border = border;
    setOrientation(VERTICAL);
  }

  void setMarkdown(String markdown) {
    removeAllViews();
    String[] lines = (markdown == null ? "" : markdown).split("\n", -1);
    for (int i = 0; i < lines.length; ) {
      if (isFence(lines[i])) {
        StringBuilder block = new StringBuilder(lines[i++]);
        while (i < lines.length) {
          block.append('\n').append(lines[i]);
          if (isFence(lines[i++])) break;
        }
        addText(block.toString(), true);
      } else if (i + 1 < lines.length && MarkdownTableParser.isSeparator(lines[i + 1])) {
        addTable(lines, i);
        i += 2;
        while (i < lines.length && lines[i].contains("|")) i++;
      } else if (lines[i].trim().isEmpty()) {
        i++;
      } else {
        StringBuilder paragraph = new StringBuilder(lines[i++]);
        while (i < lines.length
            && !lines[i].trim().isEmpty()
            && !isFence(lines[i])
            && !(i + 1 < lines.length && MarkdownTableParser.isSeparator(lines[i + 1]))) {
          paragraph.append('\n').append(lines[i++]);
        }
        addText(paragraph.toString(), false);
      }
    }
  }

  private void addText(String markdown, boolean horizontal) {
    TextView text = new TextView(getContext());
    MarkdownText.apply(text, markdown);
    text.setTextColor(foreground);
    text.setPadding(dp(8), dp(6), dp(8), dp(6));
    if (horizontal) {
      text.setTypeface(Typeface.MONOSPACE);
      text.setBackgroundColor(border & 0x18FFFFFF);
      HorizontalScrollView scroll = new HorizontalScrollView(getContext());
      scroll.setHorizontalScrollBarEnabled(true);
      scroll.addView(text, new HorizontalScrollView.LayoutParams(-2, -2));
      addView(scroll, new LayoutParams(-1, -2));
    } else {
      text.setTextIsSelectable(true);
      addView(text, new LayoutParams(-1, -2));
    }
  }

  private void addTable(String[] lines, int start) {
    List<List<String>> rows = new ArrayList<>();
    rows.add(MarkdownTableParser.split(lines[start]));
    int i = start + 2;
    while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
      rows.add(MarkdownTableParser.split(lines[i++]));
    }
    HorizontalScrollView horizontal = new HorizontalScrollView(getContext());
    horizontal.setHorizontalScrollBarEnabled(true);
    TableLayout table = new TableLayout(getContext());
    table.setStretchAllColumns(false);
    for (int r = 0; r < rows.size(); r++) {
      TableRow row = new TableRow(getContext());
      row.setBackgroundColor(r == 0 ? 0x18202020 : Color.TRANSPARENT);
      for (String value : rows.get(r)) {
        TextView cell = new TextView(getContext());
        MarkdownText.apply(cell, value);
        cell.setTextColor(foreground);
        cell.setTextIsSelectable(true);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(10), dp(7), dp(10), dp(7));
        if (r == 0) cell.setTypeface(null, Typeface.BOLD);
        cell.setBackgroundColor(border & 0x18FFFFFF);
        TableRow.LayoutParams params = new TableRow.LayoutParams(-2, -2);
        params.setMargins(0, 0, dp(1), dp(1));
        row.addView(cell, params);
      }
      table.addView(row, new TableLayout.LayoutParams(-2, -2));
    }
    horizontal.addView(table);
    addView(horizontal, new LayoutParams(-1, -2));
  }

  private static boolean isFence(String line) {
    return line.trim().startsWith("```");
  }

  private int dp(float value) {
    return (int) (value * getResources().getDisplayMetrics().density + .5f);
  }
}
