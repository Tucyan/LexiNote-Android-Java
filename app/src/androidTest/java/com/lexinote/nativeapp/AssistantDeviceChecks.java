package com.lexinote.nativeapp;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TableLayout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Device-side checks for recycled assistant UI, live SSE, and guarded background titles. */
public final class AssistantDeviceChecks {
  private static final String HOST = "feature.invalid";
  private static final ArrayBlockingQueue<FakeConnection> CONNECTIONS = new ArrayBlockingQueue<>(8);
  private static volatile boolean factoryInstalled;

  private AssistantDeviceChecks() {}

  /** Returns an empty list on success, otherwise a list of named failures. */
  public static List<String> run(Instrumentation instrumentation, MainActivity activity)
      throws Exception {
    ArrayList<String> failures = new ArrayList<>();
    State state = new State(activity);
    ArrayList<FakeConnection> fixtures = new ArrayList<>();
    TestPreferences testPreferences = null;
    boolean ready = false;
    try {
      installFactory();
      CONNECTIONS.clear();
      testPreferences = new TestPreferences(activity);
      setup(instrumentation, activity, state, testPreferences.secure);
      ready = true;
      check(
          "timeline recycling, anchor restore, and native Markdown table",
          failures,
          () -> timelineAndAnchor(instrumentation, activity));
      check(
          "real assistant SSE partial and validated final reply",
          failures,
          () -> streamingConversation(instrumentation, activity, fixtures));
      check(
          "background title success and manual-name guard",
          failures,
          () -> titleGuards(instrumentation, activity, fixtures));
    } catch (Throwable e) {
      failures.add("assistant fixture setup: " + describe(e));
    } finally {
      for (FakeConnection fixture : fixtures) fixture.release();
      CONNECTIONS.clear();
      if (ready) restore(instrumentation, activity, state);
      if (testPreferences != null) testPreferences.clear();
    }
    return failures;
  }

  private static void installFactory() {
    if (factoryInstalled) return;
    synchronized (AssistantDeviceChecks.class) {
      if (factoryInstalled) return;
      URL.setURLStreamHandlerFactory(
          protocol ->
              "https".equalsIgnoreCase(protocol)
                  ? new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL url) throws IOException {
                      if (!HOST.equalsIgnoreCase(url.getHost())) return null;
                      try {
                        FakeConnection fake = CONNECTIONS.poll(5, TimeUnit.SECONDS);
                        if (fake == null)
                          throw new IOException("No assistant fake HTTP fixture queued");
                        return fake;
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted waiting for assistant fixture", e);
                      }
                    }
                  }
                  : null);
      factoryInstalled = true;
    }
  }

  private static void setup(
      Instrumentation instrumentation, MainActivity activity, State state, SecureSettings secure)
      throws Exception {
    onMain(
        instrumentation,
        () -> {
          require(activity.active == null, "activity already has a model request");
          activity.library = new Library(state.originalLibrary.snapshot());
          activity.committed = activity.library.snapshot();
          activity.secure = secure;
          activity.session = null;
          activity.image = null;
          activity.screen = "tabs";
          activity.page = 0;
          activity.query.partialReply = "";
          activity.titles.reset();
        });
  }

  private static void timelineAndAnchor(Instrumentation instrumentation, MainActivity activity)
      throws Exception {
    onMain(
        instrumentation,
        () -> {
          JSONObject session = activity.library.newSession("assistant device timeline");
          makeTitleUserOwned(session);
          JSONArray messages = new JSONArray();
          for (int i = 0; i < 360; i++) {
            String role = i % 13 == 0 ? "system" : i % 2 == 0 ? "user" : "assistant";
            String content =
                i == 359
                    ? "| term | meaning |\n| --- | --- |\n| test | **bold** |"
                    : "device row " + i;
            JSONObject message = new JSONObject();
            Library.put(message, "id", "assistant-device-" + i);
            Library.put(message, "role", role);
            Library.put(message, "content", content);
            Library.put(message, "created_at", Library.now());
            messages.put(message);
          }
          Library.put(session, "ai_messages", messages);
          activity.library.touchSession(session, true);
          activity.session = session;
          activity.screen = "assistant";
          activity.render();
          require(activity.persist(), "could not persist timeline fixture");
        });
    instrumentation.waitForIdleSync();
    ListView list = onMain(instrumentation, () -> findList(activity.root));
    require(list != null, "assistant ListView not found");
    onMain(
        instrumentation,
        () -> {
          require(list.getHeaderViewsCount() == 1, "assistant source/context header missing");
          require(
              list.getAdapter().getCount() - list.getHeaderViewsCount() == AssistantTimeline.LIMIT,
              "timeline is not capped at latest 200 visible messages");
          require(
              list.getCount() == AssistantTimeline.LIMIT + 1, "timeline header/count is incorrect");
          require(
              list.getChildCount() > 0 && list.getChildCount() < list.getCount(),
              "ListView did not recycle offscreen rows");
          require(
              findByClass(list, TableLayout.class) != null,
              "assistant Markdown did not render a native TableLayout");
        });

    FeatureInstrumentation.capture(instrumentation, activity, "assistant-table");
    int targetPosition = 92;
    onMain(
        instrumentation,
        () ->
            list.setSelectionFromTop(targetPosition + list.getHeaderViewsCount(), activity.dp(23)));
    instrumentation.waitForIdleSync();
    AtomicReference<String> expectedId = new AtomicReference<>();
    AtomicReference<Integer> expectedOffset = new AtomicReference<>();
    onMain(
        instrumentation,
        () -> {
          activity.query.assistantView.saveAnchor();
          expectedId.set(activity.session.optString("assistant_anchor_id"));
          expectedOffset.set(activity.session.optInt("assistant_anchor_offset"));
          require(!expectedId.get().isEmpty(), "anchor message ID was not saved");
          activity.library.touchSession(activity.session, true);
          require(activity.persist(), "could not persist timeline anchor");
          activity.render();
        });
    instrumentation.waitForIdleSync();
    ListView restored = onMain(instrumentation, () -> findList(activity.root));
    require(restored != null, "assistant ListView missing after rerender");
    onMain(
        instrumentation,
        () -> {
          int position = restored.getFirstVisiblePosition() - restored.getHeaderViewsCount();
          require(position >= 0, "restored timeline is positioned on the header");
          String actualId = AssistantTimeline.id(activity.query.messages(), position);
          require(expectedId.get().equals(actualId), "anchor restored to a different message");
          View first = restored.getChildAt(0);
          if (first != null)
            require(
                Math.abs(first.getTop() - expectedOffset.get()) <= activity.dp(8),
                "anchor pixel offset was not restored");
        });
  }

  private static void streamingConversation(
      Instrumentation instrumentation, MainActivity activity, List<FakeConnection> fixtures)
      throws Exception {
    String firstJson = "{\"kind\":\"answer\",\"message\":\"partial";
    String restJson = " answer\"}";
    String firstEvent =
        event(
            new JSONObject()
                .put(
                    "choices",
                    new JSONArray()
                        .put(
                            new JSONObject()
                                .put("delta", new JSONObject().put("content", firstJson))
                                .put("finish_reason", JSONObject.NULL))));
    String suffix =
        event(
                new JSONObject()
                    .put(
                        "choices",
                        new JSONArray()
                            .put(
                                new JSONObject()
                                    .put("delta", new JSONObject().put("content", restJson))
                                    .put("finish_reason", JSONObject.NULL))))
            + event(
                new JSONObject()
                    .put(
                        "choices",
                        new JSONArray()
                            .put(
                                new JSONObject()
                                    .put("delta", new JSONObject())
                                    .put("finish_reason", "stop"))))
            + "data: [DONE]\n\n";
    FakeConnection fake =
        FakeConnection.gated(firstEvent, suffix, "text/event-stream; charset=utf-8");
    fixtures.add(fake);
    CONNECTIONS.add(fake);
    onMain(
        instrumentation,
        () -> {
          JSONObject session = activity.library.newSession("assistant stream device check");
          makeTitleUserOwned(session);
          activity.session = session;
          activity.screen = "assistant";
          activity.render();
          activity.query.send("streaming test question");
        });
    waitLatch(fake.entered, "SSE first event gate");
    await(
        instrumentation,
        "safe partial answer",
        () ->
            onMain(
                instrumentation,
                () -> activity.query.partialReply.contains("partial") && activity.active != null));
    onMain(
        instrumentation,
        () -> {
          JSONArray messages = activity.query.messages();
          require(messages.length() == 1, "partial content was persisted as a chat message");
          require(
              "user".equals(messages.optJSONObject(0).optString("role")),
              "partial content created a non-user action row");
          require(
              activity.session.optJSONObject("pending_lookup") == null
                  || activity.session.optJSONObject("pending_lookup").length() == 0,
              "partial content created an executable pending lookup");
        });
    fake.release();
    await(
        instrumentation,
        "validated final assistant answer",
        () ->
            onMain(
                instrumentation,
                () -> {
                  JSONArray messages = activity.query.messages();
                  return activity.active == null
                      && messages.length() == 2
                      && "assistant".equals(messages.optJSONObject(1).optString("role"));
                }));
    onMain(
        instrumentation,
        () -> {
          JSONObject finalMessage = activity.query.messages().optJSONObject(1);
          require(
              "answer".equals(finalMessage.optJSONObject("action").optString("kind")),
              "final streamed action was not validated/applied");
          require(
              finalMessage.optString("content").contains("partial answer"),
              "final streamed text is incomplete");
          require(!activity.session.has("last_error"), "valid SSE answer ended as an error");
        });
  }

  private static void titleGuards(
      Instrumentation instrumentation, MainActivity activity, List<FakeConnection> fixtures)
      throws Exception {
    FakeConnection success =
        FakeConnection.gated(new byte[0], titleEnvelope("Suggested title"), "application/json");
    fixtures.add(success);
    CONNECTIONS.add(success);
    AtomicReference<String> successId = new AtomicReference<>();
    onMain(
        instrumentation,
        () -> {
          JSONObject session = titleSession(activity, "title success fixture");
          successId.set(session.optString("id"));
          activity.library.touchSession(session, true);
          activity.titles.schedule(session);
        });
    waitLatch(success.entered, "title success response gate");
    success.release();
    await(
        instrumentation,
        "AI title applied",
        () ->
            onMain(
                instrumentation,
                () -> {
                  JSONObject stored = activity.library.find("conversations", successId.get());
                  return stored != null
                      && "ai".equals(stored.optJSONObject("metadata").optString("title_source"));
                }));

    FakeConnection manual =
        FakeConnection.gated(
            new byte[0], titleEnvelope("Should not replace me"), "application/json");
    fixtures.add(manual);
    CONNECTIONS.add(manual);
    AtomicReference<String> manualId = new AtomicReference<>();
    onMain(
        instrumentation,
        () -> {
          JSONObject session = titleSession(activity, "title manual guard fixture");
          manualId.set(session.optString("id"));
          activity.library.touchSession(session, true);
          activity.titles.schedule(session);
        });
    waitLatch(manual.entered, "blocked title response gate");
    onMain(
        instrumentation,
        () -> {
          JSONObject stored = activity.library.find("conversations", manualId.get());
          activity.titles.manual(stored);
          Library.put(stored, "title", "Manual title");
          JSONObject metadata = stored.optJSONObject("metadata");
          Library.put(metadata, "title", "Manual title");
          activity.library.touchSession(stored, false);
          require(activity.persist(), "could not persist manual title fixture");
        });
    manual.release();
    manual.awaitDelivered();
    instrumentation.waitForIdleSync();
    onMain(
        instrumentation,
        () -> {
          JSONObject stored = activity.library.find("conversations", manualId.get());
          require(
              "Manual title".equals(stored.optString("title")),
              "background title overwrote the manual name");
          require(
              "user".equals(stored.optJSONObject("metadata").optString("title_source")),
              "manual title ownership was lost");
        });
  }

  private static JSONObject titleSession(MainActivity activity, String text) {
    JSONObject session = activity.library.newSession(text);
    JSONObject user = new JSONObject();
    Library.put(user, "id", Library.id());
    Library.put(user, "role", "user");
    Library.put(user, "content", text);
    JSONObject assistant = new JSONObject();
    Library.put(assistant, "id", Library.id());
    Library.put(assistant, "role", "assistant");
    Library.put(assistant, "content", "A useful response.");
    JSONArray messages = new JSONArray().put(user).put(assistant);
    Library.put(session, "ai_messages", messages);
    return session;
  }

  private static void makeTitleUserOwned(JSONObject session) {
    JSONObject meta = session.optJSONObject("metadata");
    if (meta != null) Library.put(meta, "title_source", "user");
  }

  private static String event(JSONObject payload) {
    return "data: " + payload + "\n\n";
  }

  private static byte[] titleEnvelope(String title) throws Exception {
    return new JSONObject()
        .put(
            "choices",
            new JSONArray()
                .put(new JSONObject().put("message", new JSONObject().put("content", title))))
        .toString()
        .getBytes(StandardCharsets.UTF_8);
  }

  private static ListView findList(View root) {
    if (root instanceof ListView) return (ListView) root;
    if (!(root instanceof ViewGroup)) return null;
    ViewGroup group = (ViewGroup) root;
    for (int i = 0; i < group.getChildCount(); i++) {
      ListView found = findList(group.getChildAt(i));
      if (found != null) return found;
    }
    return null;
  }

  private static <T extends View> T findByClass(View root, Class<T> type) {
    if (type.isInstance(root)) return type.cast(root);
    if (!(root instanceof ViewGroup)) return null;
    ViewGroup group = (ViewGroup) root;
    for (int i = 0; i < group.getChildCount(); i++) {
      T found = findByClass(group.getChildAt(i), type);
      if (found != null) return found;
    }
    return null;
  }

  private static void await(Instrumentation instrumentation, String label, Check check)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      instrumentation.waitForIdleSync();
      if (check.ok()) return;
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for " + label);
  }

  private static void waitLatch(CountDownLatch latch, String label) throws Exception {
    if (!latch.await(5, TimeUnit.SECONDS))
      throw new AssertionError("Timed out waiting for " + label);
  }

  private static void onMain(Instrumentation instrumentation, Runnable action) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable e) {
            failure.set(e);
          }
        });
    if (failure.get() != null) throw new AssertionError(failure.get());
  }

  private static <T> T onMain(Instrumentation instrumentation, Value<T> action) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            result.set(action.get());
          } catch (Throwable e) {
            failure.set(e);
          }
        });
    if (failure.get() != null) throw new AssertionError(failure.get());
    return result.get();
  }

  private static void check(String name, List<String> failures, Throwing action) {
    try {
      action.run();
    } catch (Throwable e) {
      failures.add(name + ": " + describe(e));
    }
  }

  private static String describe(Throwable e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private interface Check {
    boolean ok() throws Exception;
  }

  private interface Value<T> {
    T get() throws Exception;
  }

  private interface Throwing {
    void run() throws Exception;
  }

  private static final class State {
    final MainActivity activity;
    final Library originalLibrary;
    final Library diskLibrary;
    final JSONObject committed;
    final JSONObject session;
    final SecureSettings secure;
    final String screen;
    final int page;
    final Uri image;
    final Uri originalImage;

    State(MainActivity a) throws Exception {
      activity = a;
      originalLibrary = a.library;
      diskLibrary = a.repository.load();
      committed = a.committed == null ? null : Library.copy(a.committed);
      session = a.session;
      secure = a.secure;
      screen = a.screen;
      page = a.page;
      image = a.image;
      originalImage = a.originalImage;
    }
  }

  private static void restore(Instrumentation instrumentation, MainActivity activity, State state)
      throws Exception {
    for (FakeConnection fixture : CONNECTIONS) fixture.release();
    onMain(
        instrumentation,
        () -> {
          activity.cancel();
          activity.titles.reset();
        });
    try {
      activity.worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
    } catch (Exception ignored) {
    }
    try {
      activity.titles.executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
    } catch (Exception ignored) {
    }
    instrumentation.waitForIdleSync();
    activity.repository.save(state.diskLibrary);
    onMain(
        instrumentation,
        () -> {
          activity.library = state.originalLibrary;
          activity.committed = state.committed;
          activity.session = state.session;
          activity.secure = state.secure;
          activity.screen = state.screen;
          activity.page = state.page;
          activity.image = state.image;
          activity.originalImage = state.originalImage;
          activity.query.partialReply = "";
          activity.render();
        });
  }

  private static final class TestPreferences {
    final ContextWrapper context;
    final SecureSettings secure;

    TestPreferences(Context base) throws Exception {
      context =
          new ContextWrapper(base) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
              return super.getSharedPreferences("assistant_device_test_" + name, mode);
            }
          };
      secure = new SecureSettings(context);
      secure.saveProvider(
          new JSONObject()
              .put("name", "Device fixture")
              .put("base_url", "https://feature.invalid/v1")
              .put("model_id", "fixture-model")
              .put("supports_vision", true),
          "device-fixture-key");
    }

    void clear() {
      context
          .getSharedPreferences("provider_private", Context.MODE_PRIVATE)
          .edit()
          .clear()
          .commit();
    }
  }

  private static final class FakeConnection extends HttpURLConnection {
    final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    final byte[] prefix, suffix;
    final String type;
    final boolean gated;
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch gate = new CountDownLatch(1);
    final CountDownLatch delivered = new CountDownLatch(1);
    volatile boolean disconnected;

    FakeConnection(byte[] prefix, byte[] suffix, String type, boolean gated) throws Exception {
      super(new URL("https://feature.invalid/v1/chat/completions"));
      this.prefix = prefix;
      this.suffix = suffix;
      this.type = type;
      this.gated = gated;
    }

    static FakeConnection gated(String prefix, String suffix, String type) throws Exception {
      return gated(
          prefix.getBytes(StandardCharsets.UTF_8), suffix.getBytes(StandardCharsets.UTF_8), type);
    }

    static FakeConnection gated(byte[] prefix, byte[] suffix, String type) throws Exception {
      return new FakeConnection(prefix, suffix, type, true);
    }

    @Override
    public void connect() {}

    @Override
    public void disconnect() {
      disconnected = true;
    }

    @Override
    public boolean usingProxy() {
      return false;
    }

    @Override
    public OutputStream getOutputStream() {
      return sent;
    }

    @Override
    public int getResponseCode() {
      return 200;
    }

    @Override
    public String getContentType() {
      return type;
    }

    @Override
    public InputStream getInputStream() {
      return new InputStream() {
        int prefixIndex, suffixIndex;
        boolean released;

        @Override
        public int read() throws IOException {
          byte[] one = new byte[1];
          int n = read(one, 0, 1);
          return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
          if (prefixIndex < prefix.length) {
            int count = Math.min(length, prefix.length - prefixIndex);
            System.arraycopy(prefix, prefixIndex, target, offset, count);
            prefixIndex += count;
            return count;
          }
          if (!released) {
            released = true;
            entered.countDown();
            if (gated) {
              try {
                if (!gate.await(5, TimeUnit.SECONDS)) throw new IOException("fixture gate timeout");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("fixture stream interrupted", e);
              }
            }
          }
          if (suffixIndex < suffix.length) {
            int count = Math.min(length, suffix.length - suffixIndex);
            System.arraycopy(suffix, suffixIndex, target, offset, count);
            suffixIndex += count;
            if (suffixIndex == suffix.length) delivered.countDown();
            return count;
          }
          delivered.countDown();
          return -1;
        }
      };
    }

    void release() {
      gate.countDown();
    }

    void awaitDelivered() throws InterruptedException {
      delivered.await(5, TimeUnit.SECONDS);
    }
  }
}
