package com.lexinote.nativeapp.storage;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.lexinote.nativeapp.storage.contract.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Process-level serial command dispatcher.
 * - Single background worker thread guarantees strict FIFO transaction execution.
 * - Callbacks to UI tokens are delivered on the main thread (or configured callback executor).
 * - unbind(token) prevents memory leaks and stale UI updates upon Activity destroy.
 */
public final class LibraryStore {

  private static volatile LibraryStore sInstance;

  private final StoreBackend backend;
  private final ExecutorService executor;
  private final Executor callbackExecutor;
  private final Set<Object> boundTokens = Collections.newSetFromMap(new WeakHashMap<>());
  private final Set<Object> unboundTokens = Collections.newSetFromMap(new WeakHashMap<>());
  private final Object tokenLock = new Object();

  public static LibraryStore getInstance(Context context) {
    if (sInstance == null) {
      synchronized (LibraryStore.class) {
        if (sInstance == null) {
          Context appCtx = context != null ? context.getApplicationContext() : null;
          File dir = (appCtx != null) ? appCtx.getFilesDir() : (context != null ? context.getFilesDir() : new File("."));
          JsonStoreBackend backend = new JsonStoreBackend(dir);
          try {
            backend.initialize();
          } catch (Exception e) {
            // Allow fallback initialization so recovery UI can operate
          }
          sInstance = new LibraryStore(backend);
        }
      }
    }
    return sInstance;
  }

  public static void setInstanceForTest(LibraryStore testInstance) {
    synchronized (LibraryStore.class) {
      if (sInstance != null && sInstance != testInstance) {
        sInstance.shutdown();
      }
      sInstance = testInstance;
    }
  }

  public static void resetInstanceForTest() {
    synchronized (LibraryStore.class) {
      if (sInstance != null) {
        sInstance.shutdown();
        sInstance = null;
      }
    }
  }

  public LibraryStore(StoreBackend backend) {
    this(
        backend,
        Executors.newSingleThreadExecutor(r -> new Thread(r, "LexiNote-StoreWorker")),
        new MainHandlerExecutor());
  }

  public LibraryStore(StoreBackend backend, ExecutorService executor, Executor callbackExecutor) {
    this.backend = Objects.requireNonNull(backend, "backend cannot be null");
    this.executor = Objects.requireNonNull(executor, "executor cannot be null");
    this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor cannot be null");
  }

  public void registerToken(Object token) {
    if (token != null) {
      synchronized (tokenLock) {
        unboundTokens.remove(token);
        boundTokens.add(token);
      }
    }
  }

  public void bind(Object token) {
    registerToken(token);
  }

  public void unbind(Object token) {
    if (token != null) {
      synchronized (tokenLock) {
        boundTokens.remove(token);
        unboundTokens.add(token);
      }
    }
  }

  public long currentGeneration() {
    return backend.getGeneration();
  }

  public long currentRevision() {
    return backend.getRevision();
  }

  public StoreBackend getBackend() {
    return backend;
  }

  /**
   * Asynchronous command submission with lifecycle token protection.
   */
  public void dispatch(Object token, LibraryCommand command, Consumer<CommitResult> callback) {
    if (token != null) {
      synchronized (tokenLock) {
        if (!unboundTokens.contains(token)) {
          boundTokens.add(token);
        }
      }
    }

    executor.execute(() -> {
      CommitResult result;
      try {
        long expectedGen = command.getExpectedGeneration();
        if (expectedGen > 0 && expectedGen != backend.getGeneration()) {
          result = CommitResult.conflict(
              expectedGen, backend.getGeneration(), command.getEventId(), "Barrier reject");
        } else {
          result = backend.execute(command);
        }
      } catch (Exception e) {
        result = CommitResult.failure(
            CommitResult.Status.IO_ERROR,
            backend.getGeneration(),
            backend.getRevision(),
            command.getEventId(),
            e.getMessage(),
            e);
      }

      final CommitResult finalResult = result;
      callbackExecutor.execute(() -> {
        boolean shouldDeliver;
        if (token == null) {
          shouldDeliver = true;
        } else {
          synchronized (tokenLock) {
            shouldDeliver = boundTokens.contains(token);
          }
        }
        if (shouldDeliver && callback != null) {
          callback.accept(finalResult);
        }
      });
    });
  }

  public void dispatch(LibraryCommand command, Consumer<CommitResult> callback) {
    dispatch(null, command, callback);
  }

  /**
   * Synchronous command execution for test runners and background services.
   * Prohibited on the Android UI thread.
   */
  public CommitResult dispatchSync(LibraryCommand command) throws Exception {
    try {
      if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
        throw new IllegalStateException("Cannot call dispatchSync on the Android main thread");
      }
    } catch (Throwable ignored) {}

    Future<CommitResult> future = executor.submit(() -> {
      long expectedGen = command.getExpectedGeneration();
      if (expectedGen > 0 && expectedGen != backend.getGeneration()) {
        return CommitResult.conflict(
            expectedGen, backend.getGeneration(), command.getEventId(), "Barrier reject");
      }
      return backend.execute(command);
    });
    return future.get();
  }

  /**
   * Asynchronous query reading an immutable snapshot.
   */
  public void query(Object token, QueryRequest request, Consumer<ReadOnlyView> callback) {
    if (token != null) {
      synchronized (tokenLock) {
        if (!unboundTokens.contains(token)) {
          boundTokens.add(token);
        }
      }
    }

    executor.execute(() -> {
      ReadOnlyView view;
      try {
        view = backend.query(request);
      } catch (Exception e) {
        view = new ReadOnlyView(null, backend.getGeneration(), backend.getRevision());
      }
      final ReadOnlyView finalView = view;
      callbackExecutor.execute(() -> {
        boolean shouldDeliver;
        if (token == null) {
          shouldDeliver = true;
        } else {
          synchronized (tokenLock) {
            shouldDeliver = boundTokens.contains(token);
          }
        }
        if (shouldDeliver && callback != null) {
          callback.accept(finalView);
        }
      });
    });
  }

  public void query(QueryRequest request, Consumer<ReadOnlyView> callback) {
    query(null, request, callback);
  }

  public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
    Future<Boolean> sentinel = executor.submit(() -> true);
    return sentinel.get(timeout, unit);
  }

  public void shutdown() {
    executor.shutdown();
    backend.close();
  }

  private static class MainHandlerExecutor implements Executor {
    private Handler handler;

    @Override
    public void execute(Runnable r) {
      if (handler == null) {
        try {
          Looper looper = Looper.getMainLooper();
          if (looper != null) {
            handler = new Handler(looper);
          }
        } catch (Throwable ignored) {}
      }
      if (handler != null) {
        handler.post(r);
      } else {
        r.run();
      }
    }
  }
}
