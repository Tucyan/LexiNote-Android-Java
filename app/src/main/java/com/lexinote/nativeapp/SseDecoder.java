package com.lexinote.nativeapp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Bounded OpenAI-compatible SSE decoder. Only choice.delta.content is exposed as preview text. */
final class SseDecoder {
  static final int MAX_BYTES = 2 * 1024 * 1024;
  static final int MAX_EVENT_CHARS = 256 * 1024;

  static final class Result {
    final String content;
    final String finishReason;
    final boolean done;

    Result(String content, String finishReason, boolean done) {
      this.content = content;
      this.finishReason = finishReason;
      this.done = done;
    }
  }

  private SseDecoder() {}

  static Result decode(InputStream input, Consumer<String> preview, Runnable cancellation)
      throws IOException {
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    StringBuilder all = new StringBuilder();
    JsonMessagePreview messagePreview = preview == null ? null : new JsonMessagePreview(preview);
    StringBuilder event = new StringBuilder();
    String finish = null;
    int bytes = 0;
    boolean done = false;
    String line;
    while ((line = boundedLine(reader)) != null) {
      cancellation.run();
      bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
      if (bytes > MAX_BYTES) throw new IOException("模型返回数据过大，请缩小查询范围");
      if (line.isEmpty()) {
        String data = event.toString();
        event.setLength(0);
        if (data.length() > 0) {
          String payload = data.toString().trim();
          if ("[DONE]".equals(payload)) {
            done = true;
            break;
          }
          JSONObject chunk;
          try {
            chunk = new JSONObject(payload);
          } catch (Exception e) {
            throw new IOException("模型流式回复格式无效", e);
          }
          org.json.JSONArray choices = chunk.optJSONArray("choices");
          if (choices != null && choices.length() > 0) {
            JSONObject choice = choices.optJSONObject(0);
            if (choice != null) {
              JSONObject delta = choice.optJSONObject("delta");
              if (delta != null && delta.has("content") && !delta.isNull("content")) {
                Object value = delta.opt("content");
                if (!(value instanceof String)) throw new IOException("模型流式内容格式无效");
                String part = (String) value;
                if ((long) all.length() + part.length() > MAX_EVENT_CHARS)
                  throw new IOException("模型回复过大，请缩小查询范围");
                all.append(part);
                if (messagePreview != null && !part.isEmpty()) messagePreview.accept(part);
              }
              String reason = choice.optString("finish_reason", "");
              if (!reason.isEmpty() && !"null".equals(reason)) finish = reason;
            }
          }
        }
      } else if (line.startsWith("data:")) {
        if (event.length() + line.length() > MAX_EVENT_CHARS) throw new IOException("模型事件过大");
        if (event.length() > 0) event.append('\n');
        event.append(line.substring(5).trim());
      }
    }
    cancellation.run();
    return new Result(all.toString(), finish, done);
  }

  private static String boundedLine(BufferedReader reader) throws IOException {
    StringBuilder line = new StringBuilder();
    int value;
    while ((value = reader.read()) != -1) {
      if (value == '\n') break;
      if (value != '\r') {
        if (line.length() >= MAX_EVENT_CHARS) throw new IOException("模型事件过大");
        line.append((char) value);
      }
    }
    return value == -1 && line.length() == 0 ? null : line.toString();
  }
}
