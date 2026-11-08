package com.lexinote.nativeapp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * High-performance, offline neural TTS pronunciation engine powered by Sherpa-onnx.
 *
 * Employs:
 * - Single-thread executor for deterministic ONNX inference and thread isolation.
 * - In-memory LRU cache to eliminate duplicate synthesis of frequent words.
 * - AudioTrack float PCM playback directly from RAM without disk I/O.
 * - Transient audio focus management and clean lifecycle preemption.
 */
public class SherpaPronunciationEngine implements PronunciationEngine {

  private final Context context;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final LruCache<String, GeneratedAudio> audioCache = new LruCache<>(40);

  private OfflineTts tts;
  private AudioTrack activeTrack;
  private AudioManager audioManager;
  private AudioFocusRequest focusRequest;
  private volatile boolean initialized = false;
  private volatile boolean released = false;

  public SherpaPronunciationEngine(Context context) {
    this.context = context.getApplicationContext();
    this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    initAsync();
  }

  public static String cleanWord(String raw) {
    if (raw == null) return "";
    String trimmed = raw.trim();
    return trimmed.replaceAll("^[\\\"'“”‘’]+|[\\\"'“”‘’.,;:!?]+$", "").trim();
  }

  private void initAsync() {
    executor.execute(() -> {
      if (released) return;
      try {
        File modelFile = TtsModelInstaller.getModelFile(context);
        File tokensFile = TtsModelInstaller.getTokensFile(context);
        File espeakDir = TtsModelInstaller.getEspeakDataDir(context);

        OfflineTtsVitsModelConfig vitsConfig = new OfflineTtsVitsModelConfig();
        vitsConfig.setModel(modelFile.getAbsolutePath());
        vitsConfig.setTokens(tokensFile.getAbsolutePath());
        if (espeakDir != null) {
          vitsConfig.setDataDir(espeakDir.getAbsolutePath());
        }
        vitsConfig.setNoiseScale(0.667f);
        vitsConfig.setNoiseScaleW(0.8f);
        vitsConfig.setLengthScale(1.0f);

        OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
        modelConfig.setVits(vitsConfig);
        modelConfig.setNumThreads(2);
        modelConfig.setDebug(false);
        modelConfig.setProvider("cpu");

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(modelConfig);

        tts = new OfflineTts(null, config);
        initialized = true;
      } catch (Throwable t) {
        initialized = false;
      }
    });
  }

  @Override
  public boolean isReady() {
    return initialized && tts != null && !released;
  }

  @Override
  public void pronounce(String word, Runnable onStart, Runnable onComplete, Consumer<Throwable> onError) {
    if (released) {
      if (onError != null) {
        onError.accept(new IllegalStateException("PronunciationEngine has been released"));
      }
      return;
    }

    final String clean = cleanWord(word);
    if (clean.isEmpty()) {
      return;
    }

    executor.execute(() -> {
      if (released) return;
      try {
        if (!initialized || tts == null) {
          throw new IllegalStateException("TTS engine not yet ready");
        }

        stopPlayback();

        GeneratedAudio audio = audioCache.get(clean);
        if (audio == null) {
          audio = tts.generate(clean, 0, 1.0f);
          if (audio != null) {
            audioCache.put(clean, audio);
          }
        }

        if (audio == null || audio.getSamples() == null || audio.getSamples().length == 0) {
          throw new RuntimeException("Generated audio empty for: " + clean);
        }

        final GeneratedAudio finalAudio = audio;
        mainHandler.post(() -> {
          if (released) return;
          if (onStart != null) onStart.run();
          playAudio(finalAudio, onComplete, onError);
        });
      } catch (Throwable t) {
        mainHandler.post(() -> {
          if (onError != null) onError.accept(t);
        });
      }
    });
  }

  private synchronized void playAudio(GeneratedAudio audio, Runnable onComplete, Consumer<Throwable> onError) {
    if (released) return;
    try {
      float[] samples = audio.getSamples();
      int sampleRate = audio.getSampleRate();

      stopPlayback();

      AudioAttributes attributes = new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build();

      AudioFormat format = new AudioFormat.Builder()
          .setSampleRate(sampleRate)
          .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build();

      int bufferSizeInBytes = samples.length * 4;
      activeTrack = new AudioTrack.Builder()
          .setAudioAttributes(attributes)
          .setAudioFormat(format)
          .setBufferSizeInBytes(bufferSizeInBytes)
          .setTransferMode(AudioTrack.MODE_STATIC)
          .build();

      activeTrack.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);

      if (audioManager != null) {
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> {
              if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                stopPlayback();
              }
            })
            .build();
        audioManager.requestAudioFocus(focusRequest);
      }

      final AudioTrack currentTrack = activeTrack;
      currentTrack.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
        @Override
        public void onMarkerReached(AudioTrack track) {
          stopPlayback();
          if (onComplete != null) {
            mainHandler.post(onComplete);
          }
        }

        @Override
        public void onPeriodicNotification(AudioTrack track) {}
      });
      currentTrack.setNotificationMarkerPosition(samples.length);

      currentTrack.play();
    } catch (Throwable t) {
      stopPlayback();
      if (onError != null) {
        onError.accept(t);
      }
    }
  }

  private synchronized void stopPlayback() {
    if (activeTrack != null) {
      try {
        if (activeTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
          activeTrack.stop();
        }
        activeTrack.release();
      } catch (Throwable ignored) {}
      activeTrack = null;
    }
    if (audioManager != null && focusRequest != null) {
      try {
        audioManager.abandonAudioFocusRequest(focusRequest);
      } catch (Throwable ignored) {}
      focusRequest = null;
    }
  }

  @Override
  public void stop() {
    executor.execute(this::stopPlayback);
  }

  @Override
  public void release() {
    released = true;
    stopPlayback();
    executor.execute(() -> {
      if (tts != null) {
        tts.release();
        tts = null;
      }
      initialized = false;
      audioCache.evictAll();
    });
    executor.shutdown();
  }
}
