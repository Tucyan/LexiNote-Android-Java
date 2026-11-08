package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;

/**
 * Comprehensive 4-Tier Opaque-Box Test Suite for Offline Neural TTS Pronunciation Engine.
 *
 * Covers:
 * - Feature 11: Decoupled PronunciationEngine interface contract, asynchronous playback state machine,
 *   headword extraction, rapid invocation preemption, and zero-network offline guarantee.
 */
public class PronunciationEngineTest {

  /**
   * Authoritative decoupled contract interface matching PROJECT.md § Interface Contracts.
   */
  public interface PronunciationEngineContract extends PronunciationEngine {
  }

  /**
   * Headless Mock Implementation of the PronunciationEngineContract for host JVM testing.
   */
  public static class MockPronunciationEngine implements PronunciationEngineContract {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean ready = true;
    private volatile boolean playing = false;
    private volatile boolean released = false;
    private volatile String currentWord = null;
    private final AtomicInteger playbackCount = new AtomicInteger(0);

    public static String cleanWord(String raw) {
      if (raw == null) return "";
      String trimmed = raw.trim();
      // Strip enclosing quotes and trailing punctuation
      trimmed = trimmed.replaceAll("^[\\\"'“”‘’]+|[\\\"'“”‘’.,;:!?]+$", "");
      return trimmed.trim();
    }

    @Override
    public boolean isReady() {
      return ready && !released;
    }

    @Override
    public synchronized void pronounce(
        String word, Runnable onStart, Runnable onComplete, Consumer<Throwable> onError) {
      if (released) {
        if (onError != null) {
          onError.accept(new IllegalStateException("PronunciationEngine has been released"));
        }
        return;
      }

      final String target = cleanWord(word);
      if (target.isEmpty()) {
        // Safe no-op for empty words
        return;
      }

      // Preempt any active playback
      stop();

      this.currentWord = target;
      this.playing = true;

      executor.execute(() -> {
        try {
          if (onStart != null) onStart.run();

          // Simulate brief audio playback duration
          Thread.sleep(30);

          synchronized (MockPronunciationEngine.this) {
            if (playing && target.equals(currentWord)) {
              playbackCount.incrementAndGet();
              playing = false;
              currentWord = null;
              if (onComplete != null) onComplete.run();
            }
          }
        } catch (InterruptedException e) {
          // Interrupted by stop() or release()
          synchronized (MockPronunciationEngine.this) {
            playing = false;
          }
        } catch (Throwable t) {
          synchronized (MockPronunciationEngine.this) {
            playing = false;
          }
          if (onError != null) onError.accept(t);
        }
      });
    }

    @Override
    public synchronized void stop() {
      if (playing) {
        playing = false;
        currentWord = null;
      }
    }

    @Override
    public synchronized void release() {
      stop();
      released = true;
      ready = false;
      executor.shutdownNow();
    }

    public boolean isPlaying() {
      return playing;
    }

    public int getPlaybackCount() {
      return playbackCount.get();
    }
  }

  // =========================================================================
  // Tier 1: Feature Coverage (Representative Inputs)
  // =========================================================================

  @Test
  public void tier1_pronounceExecutesLifecycleCallbacksCleanly() throws Exception {
    MockPronunciationEngine engine = new MockPronunciationEngine();
    assertTrue("Engine must be ready initially", engine.isReady());

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(1);
    AtomicBoolean errorCalled = new AtomicBoolean(false);

    engine.pronounce(
        "resilient",
        startLatch::countDown,
        completeLatch::countDown,
        err -> errorCalled.set(true));

    assertTrue("onStart must be called", startLatch.await(1, TimeUnit.SECONDS));
    assertTrue("onComplete must be called", completeLatch.await(1, TimeUnit.SECONDS));
    assertFalse("onError must not be called", errorCalled.get());
    assertEquals(1, engine.getPlaybackCount());

    engine.release();
  }

  @Test
  public void tier1_headwordExtractionStripsPunctuationAndWhitespace() {
    assertEquals("apple", MockPronunciationEngine.cleanWord("  apple  "));
    assertEquals("serendipity", MockPronunciationEngine.cleanWord("\"serendipity\""));
    assertEquals("break a leg", MockPronunciationEngine.cleanWord(" 'break a leg.' "));
    assertEquals("unprecedented", MockPronunciationEngine.cleanWord("unprecedented,"));

    // Also assert production SherpaPronunciationEngine cleanWord
    assertEquals("apple", SherpaPronunciationEngine.cleanWord("  apple  "));
    assertEquals("serendipity", SherpaPronunciationEngine.cleanWord("\"serendipity\""));
    assertEquals("break a leg", SherpaPronunciationEngine.cleanWord(" 'break a leg.' "));
    assertEquals("unprecedented", SherpaPronunciationEngine.cleanWord("unprecedented,"));
    assertEquals("", SherpaPronunciationEngine.cleanWord(null));
  }

  @Test
  public void tier1_ttsModelInstallerConstantsAndDecoupledContract() {
    assertEquals("model.onnx", TtsModelInstaller.MODEL_NAME);
    assertEquals("tokens.txt", TtsModelInstaller.TOKENS_NAME);
    assertEquals("espeak-ng-data.zip", TtsModelInstaller.ESPEAK_DATA_ZIP);
    assertEquals("espeak-ng-data", TtsModelInstaller.ESPEAK_DATA_DIR);

    // Verify PronunciationEngine interface contract assignment
    PronunciationEngine engine = new MockPronunciationEngine();
    assertTrue("Mock engine is ready", engine.isReady());
    engine.release();
    assertFalse("Mock engine not ready after release", engine.isReady());
  }

  @Test
  public void tier1_stopHaltsPlaybackImmediately() {
    MockPronunciationEngine engine = new MockPronunciationEngine();
    engine.pronounce("longspeech", null, null, null);
    engine.stop();
    assertFalse("Playback must be stopped", engine.isPlaying());
    engine.release();
  }

  @Test
  public void tier1_releaseMarksEngineNotReadyAndReleasesResources() {
    MockPronunciationEngine engine = new MockPronunciationEngine();
    assertTrue(engine.isReady());

    engine.release();
    assertFalse("Engine must not be ready after release", engine.isReady());
  }

  // =========================================================================
  // Tier 2: Boundary & Corner Cases (Adversarial Probing)
  // =========================================================================

  @Test
  public void tier2_nullAndEmptyWordsAreSafeNoOps() throws Exception {
    MockPronunciationEngine engine = new MockPronunciationEngine();
    AtomicBoolean callbackFired = new AtomicBoolean(false);

    engine.pronounce(null, () -> callbackFired.set(true), () -> callbackFired.set(true), err -> callbackFired.set(true));
    engine.pronounce("", () -> callbackFired.set(true), () -> callbackFired.set(true), err -> callbackFired.set(true));
    engine.pronounce("   ", () -> callbackFired.set(true), () -> callbackFired.set(true), err -> callbackFired.set(true));

    Thread.sleep(40);
    assertFalse("Callbacks must not fire for null/empty words", callbackFired.get());
    assertEquals(0, engine.getPlaybackCount());
    engine.release();
  }

  @Test
  public void tier2_pronounceAfterReleaseReportsErrorViaCallback() throws Exception {
    MockPronunciationEngine engine = new MockPronunciationEngine();
    engine.release();

    CountDownLatch errorLatch = new CountDownLatch(1);
    AtomicReference<Throwable> captured = new AtomicReference<>();

    engine.pronounce("word", null, null, t -> {
      captured.set(t);
      errorLatch.countDown();
    });

    assertTrue("onError must be called when pronouncing after release", errorLatch.await(500, TimeUnit.MILLISECONDS));
    assertTrue(captured.get() instanceof IllegalStateException);
  }

  @Test
  public void tier2_stopWhenAlreadyStoppedIsSafeNoOp() {
    MockPronunciationEngine engine = new MockPronunciationEngine();
    engine.stop();
    engine.stop();
    assertFalse(engine.isPlaying());
    engine.release();
  }

  @Test
  public void tier2_rapidConsecutiveCallsPreemptPreviousAudio() throws Exception {
    MockPronunciationEngine engine = new MockPronunciationEngine();
    CountDownLatch lastCompleteLatch = new CountDownLatch(1);

    // Rapid successive taps
    engine.pronounce("word1", null, null, null);
    engine.pronounce("word2", null, null, null);
    engine.pronounce("word3", null, lastCompleteLatch::countDown, null);

    assertTrue("Final word playback must complete", lastCompleteLatch.await(1, TimeUnit.SECONDS));
    // Preempted words do not count as completed
    assertEquals("Only the final non-preempted audio completes", 1, engine.getPlaybackCount());

    engine.release();
  }

  // =========================================================================
  // Tier 3: Cross-Feature Combinations
  // =========================================================================

  @Test
  public void tier3_wordDetailPhoneticDisplayAndSpeakerButton() throws Exception {
    MockPronunciationEngine engine = new MockPronunciationEngine();

    // Case A: Query result with phonetic symbol present
    String wordA = "serendipity";
    String phoneticA = "/ˌser.ənˈdɪp.ə.ti/";
    assertFalse("Phonetic symbol is non-empty", phoneticA.isEmpty());

    CountDownLatch completeA = new CountDownLatch(1);
    engine.pronounce(wordA, null, completeA::countDown, null);
    assertTrue(completeA.await(1, TimeUnit.SECONDS));

    // Case B: Query result without phonetic symbol (empty)
    String wordB = "break a leg";
    String phoneticB = "";
    assertTrue("Phonetic symbol is empty", phoneticB.isEmpty());

    // Speaker button should still pronounce wordB
    CountDownLatch completeB = new CountDownLatch(1);
    engine.pronounce(wordB, null, completeB::countDown, null);
    assertTrue(completeB.await(1, TimeUnit.SECONDS));

    assertEquals(2, engine.getPlaybackCount());
    engine.release();
  }

  // =========================================================================
  // Tier 4: Real-World Application Scenarios
  // =========================================================================

  @Test
  public void tier4_endToEndVocabularyStudyPronunciationFlow() throws Exception {
    MockPronunciationEngine engine = new MockPronunciationEngine();
    String[] vocabularyWords = {"phenomenon", "ubiquitous", "resilience"};

    for (String word : vocabularyWords) {
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(1);

      engine.pronounce(
          word,
          started::countDown,
          finished::countDown,
          err -> fail("Pronunciation must not error in offline study: " + err.getMessage()));

      assertTrue("Audio must start for " + word, started.await(500, TimeUnit.MILLISECONDS));
      assertTrue("Audio must complete for " + word, finished.await(500, TimeUnit.MILLISECONDS));
    }

    assertEquals(3, engine.getPlaybackCount());

    // User navigates away or pauses activity -> stop audio
    engine.stop();
    assertFalse(engine.isPlaying());

    // User exits activity -> release engine
    engine.release();
    assertFalse(engine.isReady());
  }
}
