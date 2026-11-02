package com.lexinote.nativeapp.storage.contract;

import java.io.File;
import java.util.List;
import org.json.JSONObject;

/**
 * Abstract storage SPI enabling interchangeable persistence engines:
 * - Interim: JsonStoreBackend (wraps Repository + Library)
 * - Future: SqliteLibraryStore (SQLite incremental transactions in Task 3)
 */
public interface StoreBackend extends AutoCloseable {

  /**
   * Initializes backend resources (reads disk snapshot or opens SQLite tables).
   */
  void initialize() throws Exception;

  /**
   * Active generation barrier. Incremented monotonically upon restore, rollback, or migration.
   */
  long getGeneration();

  /**
   * Active revision counter within the current generation. Increments on every committed mutation.
   */
  long getRevision();

  /**
   * Returns an immutable read-only snapshot view of the entire library.
   */
  ReadOnlyView loadSnapshot() throws Exception;

  /**
   * Executes a granular read query against the backend.
   */
  ReadOnlyView query(QueryRequest request) throws Exception;

  /**
   * Atomically executes a single LibraryCommand.
   */
  CommitResult execute(LibraryCommand command) throws Exception;

  /**
   * Atomically executes a batch of commands within a single storage transaction.
   */
  CommitResult applyTransaction(List<LibraryCommand> commands) throws Exception;

  /**
   * Restores a full backup JSON, atomically creating a new generation barrier.
   */
  CommitResult restore(JSONObject backup, long expectedGeneration) throws Exception;

  /**
   * Rolls back to pre-restore snapshot, creating a new generation barrier.
   */
  CommitResult rollback(long expectedGeneration) throws Exception;

  /**
   * Migrates from legacy library.json file into this backend.
   */
  void migrateFrom(File legacyFile) throws Exception;

  @Override
  void close();
}
