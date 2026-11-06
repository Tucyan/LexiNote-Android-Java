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
  private volatile long publishedGeneration = 1L, publishedRevision;
  private volatile Exception initializationFailure;
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
          com.lexinote.nativeapp.SqliteLibraryStore backend = new com.lexinote.nativeapp.SqliteLibraryStore(
              appCtx != null ? appCtx : context, "library.db", new File(dir, "library.json"));
          sInstance = new LibraryStore(backend);
          LibraryStore instance = sInstance;
          instance.executor.execute(() -> {
            try { backend.initialize(); instance.publishVersion(); }
            catch (Exception error) { instance.initializationFailure = error; try { instance.publishVersion(); } catch (Exception unavailable) { } }
          });
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
    return publishedGeneration;
  }

  public long currentRevision() {
    return publishedRevision;
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
        if (initializationFailure != null && command.getType() != LibraryCommand.Type.IMPORT_LIBRARY)
          throw initializationFailure;
        long expectedGen = command.getExpectedGeneration();
        if (expectedGen > 0 && expectedGen != backend.getGeneration()) {
          result = CommitResult.conflict(
              expectedGen, backend.getGeneration(), command.getEventId(), "Barrier reject");
        } else {
          result = backend.execute(command);
          if (result.isSuccess() && command.getType() == LibraryCommand.Type.IMPORT_LIBRARY)
            initializationFailure = null;
        }
      } catch (Exception e) {
        result = CommitResult.failure(
            CommitResult.Status.IO_ERROR,
            publishedGeneration,
            publishedRevision,
            command.getEventId(),
            e.getMessage(),
            e);
      }

      publishVersion();
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
    Looper main = null, current = null;
    try { main = Looper.getMainLooper(); current = Looper.myLooper(); }
    catch (RuntimeException unavailableInJvmTests) { }
    if (main != null && current == main)
      throw new IllegalStateException("Cannot call dispatchSync on the Android main thread");

    Future<CommitResult> future = executor.submit(() -> {
      long expectedGen = command.getExpectedGeneration();
      if (expectedGen > 0 && expectedGen != backend.getGeneration()) {
        return CommitResult.conflict(
            expectedGen, backend.getGeneration(), command.getEventId(), "Barrier reject");
      }
      CommitResult result = backend.execute(command);
      publishVersion();
      return result;
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
        view = new ReadOnlyView(null, publishedGeneration, publishedRevision);
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

  public interface StoreWork<T> { T run(StoreBackend backend) throws Exception; }

  /** All database/backup work shares the command queue and generation barrier. */
  public <T> void runExclusive(Object token, StoreWork<T> work,
      java.util.function.BiConsumer<T, Exception> callback) {
    runExclusive(token, false, work, callback);
  }

  /** Only an explicit import may recover an unavailable initial database. */
  public <T> void runRecoveryExclusive(Object token, StoreWork<T> work,
      java.util.function.BiConsumer<T, Exception> callback) {
    runExclusive(token, true, work, callback);
  }

  private <T> void runExclusive(Object token, boolean recovery, StoreWork<T> work,
      java.util.function.BiConsumer<T, Exception> callback) {
    executor.execute(() -> {
      T value = null; Exception failure = null;
      try {
        if (!recovery && initializationFailure != null) throw initializationFailure;
        value = work.run(backend); publishVersion();
        if (recovery && value instanceof CommitResult && ((CommitResult) value).isSuccess()) initializationFailure = null;
      }
      catch (Exception error) { failure = error; }
      final T delivered = value; final Exception error = failure;
      callbackExecutor.execute(() -> {
        synchronized (tokenLock) { if (token != null && !boundTokens.contains(token)) return; }
        callback.accept(delivered, error);
      });
    });
  }

  private void publishVersion() {
    try {
      long generation = backend.getGeneration();
      long revision = backend.getRevision();
      publishedGeneration = generation;
      publishedRevision = revision;
    } catch (RuntimeException unavailableDatabase) {
      // Retain the last published pair so a failed open still receives its callback.
    }
  }

  /** Constructs the detached display model entirely on the storage worker. */
  public void loadLibrary(Object token, Consumer<CommitResult> callback) {
    executor.execute(() -> {
      CommitResult result;
      try {
        if (initializationFailure != null) throw initializationFailure;
        com.lexinote.nativeapp.Library library = new com.lexinote.nativeapp.Library(
            backend.loadSnapshot().getRawDocument());
        publishVersion();
        result = CommitResult.success(publishedGeneration, publishedRevision, "", null,
            Collections.emptyList(), "Loaded", library);
      } catch (Exception error) {
        result = CommitResult.failure(CommitResult.Status.IO_ERROR, publishedGeneration,
            publishedRevision, "", error.getMessage(), error);
      }
      final CommitResult delivered = result;
      callbackExecutor.execute(() -> {
        synchronized (tokenLock) { if (token != null && !boundTokens.contains(token)) return; }
        callback.accept(delivered);
      });
    });
  }

  public synchronized void shutdown() {
    if (executor.isShutdown()) return;
    executor.execute(backend::close);
    executor.shutdown();
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
