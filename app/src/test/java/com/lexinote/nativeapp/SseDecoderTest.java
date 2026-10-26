package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class SseDecoderTest {
  @Test
  public void tableCellsRespectEscapedPipesAndInlineCode() {
    assertEquals(
        java.util.Arrays.asList("a|b", "`c|d`", "e"),
        MarkdownTableParser.split("| a\\|b | `c|d` | e |"));
    assertTrue(MarkdownTableParser.isSeparator("| --- | :---: |"));
    assertFalse(MarkdownTableParser.isSeparator("| --- | text |"));
  }

  @Test
  public void extractsOnlyPlainMessageContentAndFinishReason() throws Exception {
    String payload = "{\"kind\":\"answer\",\"message\":\"你\"}";
    String chunk =
        new org.json.JSONObject()
            .put(
                "choices",
                new org.json.JSONArray()
                    .put(
                        new org.json.JSONObject()
                            .put("delta", new org.json.JSONObject().put("content", payload))
                            .put("finish_reason", org.json.JSONObject.NULL)))
            .toString();
    String wire =
        "data: "
            + chunk
            + "\n\n"
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";
    AtomicReference<String> preview = new AtomicReference<>("");
    SseDecoder.Result result =
        SseDecoder.decode(
            new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)),
            part -> preview.set(preview.get() + part),
            () -> {});
    assertEquals(payload, result.content);
    assertEquals("你", preview.get());
    assertEquals("stop", result.finishReason);
    assertTrue(result.done);
  }

  @Test
  public void doesNotPreviewNestedActionMessageOrOtherJsonFields() {
    java.util.concurrent.atomic.AtomicReference<String> output =
        new java.util.concurrent.atomic.AtomicReference<>("");
    JsonMessagePreview scanner = new JsonMessagePreview(part -> output.set(output.get() + part));
    scanner.accept(
        "{\"result\":{\"message\":\"unsafe\"},\"kind\":\"answer\",\"message\":\"safe\\ntext\"}");
    assertEquals("safe\ntext", output.get());
  }

  @Test
  public void boundsContentAndChecksCancellation() throws Exception {
    String huge =
        "data: {\"choices\":[{\"delta\":{\"content\":\""
            + "x".repeat(SseDecoder.MAX_EVENT_CHARS + 1)
            + "\"}}]}\n\n";
    try {
      SseDecoder.decode(
          new ByteArrayInputStream(huge.getBytes(StandardCharsets.UTF_8)), null, () -> {});
      fail();
    } catch (java.io.IOException expected) {
    }
    try {
      SseDecoder.decode(
          new ByteArrayInputStream(new byte[0]),
          null,
          () -> {
            throw new java.util.concurrent.CancellationException();
          });
      fail();
    } catch (java.util.concurrent.CancellationException expected) {
    }
  }
}
