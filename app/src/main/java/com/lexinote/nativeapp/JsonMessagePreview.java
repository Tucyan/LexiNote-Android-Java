package com.lexinote.nativeapp;

import java.util.function.Consumer;

/** Incremental JSON string scanner that exposes only the direct, top-level `message` string. */
final class JsonMessagePreview {
  private int depth;
  private boolean inString;
  private boolean escaped;
  private boolean unicode;
  private int unicodeDigits;
  private int unicodeValue;
  private boolean keyString;
  private boolean messageValue;
  private char prior;
  private final StringBuilder token = new StringBuilder();
  private StringBuilder emittedBuffer;
  private final Consumer<String> output;

  JsonMessagePreview(Consumer<String> output) {
    this.output = output;
  }

  void accept(String chunk) {
    StringBuilder emitted = new StringBuilder();
    emittedBuffer = emitted;
    for (int i = 0; i < chunk.length(); i++) {
      char c = chunk.charAt(i);
      if (inString) {
        if (unicode) {
          int digit = Character.digit(c, 16);
          if (digit < 0) {
            unicode = false;
            unicodeDigits = 0;
            continue;
          }
          unicodeValue = (unicodeValue << 4) | digit;
          if (++unicodeDigits == 4) {
            appendDecoded((char) unicodeValue);
            unicode = false;
            unicodeDigits = 0;
          }
          continue;
        }
        if (escaped) {
          escaped = false;
          if (c == 'u') {
            unicode = true;
            unicodeDigits = 0;
            unicodeValue = 0;
          } else appendDecoded(c == 'n' ? '\n' : c == 'r' ? '\r' : c == 't' ? '\t' : c);
        } else if (c == '\\') escaped = true;
        else if (c == '"') {
          inString = false;
          if (keyString) {
            messageValue = depth == 1 && "message".contentEquals(token);
            keyString = false;
            prior = '"';
          } else {
            messageValue = false;
            prior = '"';
          }
          token.setLength(0);
        } else appendDecoded(c);
        continue;
      }
      if (c == '"') {
        inString = true;
        keyString = depth == 1 && (prior == '{' || prior == ',');
        token.setLength(0);
        continue;
      }
      if (!Character.isWhitespace(c)) {
        if (c == '{') depth++;
        else if (c == '}') {
          depth--;
          messageValue = false;
        } else if (c == ':' && messageValue) {
          /* next string is the target value */
        } else if (c == ',' && depth == 1) messageValue = false;
        else if (depth == 1 && messageValue && c != ':') messageValue = false;
        prior = c;
      }
    }
    if (emitted.length() > 0 && output != null) output.accept(emitted.toString());
    emittedBuffer = null;
  }

  private void appendDecoded(char c) {
    if (keyString) token.append(c);
    else if (messageValue) emittedBuffer.append(c);
  }
}
