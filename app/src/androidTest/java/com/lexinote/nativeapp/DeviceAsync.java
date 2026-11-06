package com.lexinote.nativeapp;

import android.app.Instrumentation;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Test-thread waits for real storage acknowledgements, never blocking Android's main thread. */
final class DeviceAsync {
  interface Action { void run() throws Exception; }
  private DeviceAsync() {}
  static void ready(Instrumentation instrumentation, MainActivity activity) throws Exception {
    long end = android.os.SystemClock.elapsedRealtime() + 15000;
    while (android.os.SystemClock.elapsedRealtime() < end) {
      boolean[] ready = new boolean[1];
      instrumentation.runOnMainSync(() -> ready[0] = activity.library != null && activity.query != null);
      if (ready[0]) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Activity storage startup timed out");
  }
  static void save(Instrumentation instrumentation, MainActivity activity, Action setup, boolean expected) throws Exception {
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(() -> {
      try {
        if (setup != null) setup.run();
        activity.persistAsync(saved -> {
          if (saved != expected) failure.set(new AssertionError("Persistence acknowledgement mismatch: " + saved));
          completed.countDown();
        });
      } catch (Throwable e) { failure.set(e); completed.countDown(); }
    });
    if (!completed.await(15, TimeUnit.SECONDS)) throw new AssertionError("Persistence acknowledgement timed out");
    if (failure.get() != null) throw new AssertionError("Persistence failed", failure.get());
    instrumentation.waitForIdleSync();
  }
  static void change(Instrumentation instrumentation, MainActivity activity, Action action) throws Exception {
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    instrumentation.runOnMainSync(() -> {
      try {
        activity.changeAsync(() -> {
          try { action.run(); } catch (Exception e) { throw new RuntimeException(e); }
        }, saved -> { if (!saved) failure.set(new AssertionError("Change rejected")); completed.countDown(); });
      } catch (Throwable e) { failure.set(e); completed.countDown(); }
    });
    if (!completed.await(15, TimeUnit.SECONDS)) throw new AssertionError("Change acknowledgement timed out");
    if (failure.get() != null) throw new AssertionError("Change failed", failure.get());
    instrumentation.waitForIdleSync();
  }
  static void drain(Instrumentation instrumentation, MainActivity activity) throws Exception {
    activity.store.awaitIdle(15, TimeUnit.SECONDS); instrumentation.waitForIdleSync();
    activity.store.awaitIdle(15, TimeUnit.SECONDS); instrumentation.waitForIdleSync();
  }
}
