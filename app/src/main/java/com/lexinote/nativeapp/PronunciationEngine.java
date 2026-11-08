package com.lexinote.nativeapp;

import java.util.function.Consumer;

/**
 * Decoupled pronunciation engine contract for LexiNote English voice playback.
 * Host JVM unit tests can run against mocks without loading native ONNX/C++ libraries.
 */
public interface PronunciationEngine {

  /**
   * Asynchronously synthesizes and plays audio pronunciation for the specified word.
   *
   * @param word       the English headword or phrase to pronounce
   * @param onStart    callback invoked when playback commences
   * @param onComplete callback invoked when playback finishes normally
   * @param onError    callback invoked if synthesis or playback fails
   */
  void pronounce(String word, Runnable onStart, Runnable onComplete, Consumer<Throwable> onError);

  /**
   * Stops any currently active audio playback immediately.
   */
  void stop();

  /**
   * Releases underlying neural model resources, audio tracks, and worker threads.
   */
  void release();

  /**
   * Checks if the engine is initialized and ready for synthesis.
   *
   * @return true if ready, false otherwise
   */
  boolean isReady();
}
