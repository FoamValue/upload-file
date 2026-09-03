# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> 🇨🇳 [简体中文](CHANGELOG.zh-CN.md)

## [Unreleased]

### Planned

- Spring Boot 3/4 (`jakarta.servlet`) adapter (deferred from rc.4; core already works on Jakarta stacks
  via manual wiring).

## [1.0.0-rc.4] - 2026-09-03

**Feedback-driven release.** Addressed the integration feedback from a real consumer
(`doc/user-feedback/upload-file-usage-feedback.md`, path-finder commit `62ae062`): a stable read/cancel
contract for the confirm phase, stable HTTP error semantics for core failures, and documented cleanup /
manual-wiring semantics.

### Added

- **Stable per-identifier read** – `ResumableUploadService.getTask(identifier)` returns the current
  task whose `finalPath` is the authoritative merged-artifact location for the confirm phase (no more
  guessing the directory layout).
- **Explicit task cancellation** – `ResumableUploadService.cancelUpload(identifier [, token])` removes
  the task record, its chunks and the merged artifact dir; returns `false` when nothing existed and
  throws `409` while an async merge is pending/running. Exposed over HTTP as
  `POST /upload?action=cancel&identifier=...` (new `AccessControl.ACTION_CANCEL`).
- **Stable error semantics** – core failures now implement `UploadErrorCode.getHttpStatusCode()`:
  the existing `ChecksumMismatchException` (`400`) / `AccessDeniedException` (`401`) /
  `QuotaExceededException` (`507`) plus three new typed exceptions, `UploadValidationException` (`400`),
  `UploadTaskNotFoundException` (`404`) and `UploadMergeConflictException` (`409`). The new types
  subclass their generic Java counterparts (`IllegalArgumentException`, `NoSuchElementException`,
  `IllegalStateException`), so existing broad catches keep working; integrations only need one
  `UploadErrorCode` check to map statuses.
- **Servlet mapping parity** – `UploadServlet` now maps failures via `UploadErrorCode` (yielding
  `400/401/404/409/507` as documented) and registers the `cancel` action.

### Changed

- `merge`/`submitMerge`/chunk validation throw the typed exceptions above instead of the raw generic
  ones — same failure cases, now with a stable HTTP status.
- README / docs/API now document the confirm-phase `finalPath` contract, `getTask`/`cancelUpload`,
  cleanup and orphan reclamation semantics, manual (core) wiring responsibilities for the
  `upload-file.*` properties, the `UploadErrorCode` status table, and the AccessControl note for
  existing-login (Bearer/SSO) integrations.

## [1.0.0-rc.3] - 2026-08-29

**Pre-release.** Production-readiness hardening before the `1.0.0` GA. All new features are off by
default, so upgrading from `rc.2` keeps the existing behavior byte-for-byte (regression-covered by
the new compat suite).

### Added

- **Access control (T6)**: `AccessControl` SPI with `PermitAllAccessControl` (default, no-op) and
  `TokenAccessControl` (constant-time shared-token comparison); every upload/progress/merge/async-merge/
  download entry point is checked; missing or wrong tokens return `401`
- **File size & capacity quota (T8)**: `max-file-size` per-file limit (checked on first chunk and
  before merge) and optional `quota.max-bytes` global capacity quota (approximate, returns
  `507 Insufficient Storage`)
- **Cleanup observability (T9)**: `CleanupStats` snapshot (`getLastStats()`) plus a stats listener;
  the integrations log one structured line per pass (`observability.log-stats`, default `true`)
- **Task-store migration (T10)**: `TaskStoreMigrator` copies in-flight tasks between stores
  (e.g. `FileTaskStore` → `JdbcTaskStore` / `RedisTaskStore`); explicit and idempotent, never
  runs automatically (`migration.enabled` exposes the bean)
- **Metadata schema version (T11)**: `UploadTask.schemaVersion` (current `1`); records missing the
  field are normalized to `1` on load
- **Multi-instance cleanup lock (T7)**: `CleanupLock` SPI with `RedisCleanupLock` (`SET NX EX` lease)
  in the redis module; when the lease cannot be acquired a pass is skipped (`cleanup.use-redis-lock`)
- New properties: `security.enabled`, `security.token`, `security.header-name`, `max-file-size`,
  `quota.max-bytes`, `cleanup.use-redis-lock`, `observability.log-stats`, `migration.enabled`;
  matching Servlet init-params
- **Compat regression suite (T12)**: boots against rc.2 metadata JSON + directory layout and
  verifies default-config behavior; covers the two high-risk combinations (C1: silent TTL deletion,
  C2: in-memory store + orphan GC)

### Changed

- `ResumableUploadService` / `ResumableDownloadService` gained token-carrying overloads
  (`uploadChunk(req, token, in)`, `merge(id, token)`, `resolveFile(id, token)`, ...); the old
  signatures delegate with no token and are unchanged
- Enabling `security.enabled` without a token fails fast at startup (servlet context and
  Spring Boot) so a misconfiguration never silently opens the endpoints

### Security

- Optional shared-token access control on every endpoint (constant-time comparison, off by default)
- `max-file-size` / `max-file-size` init-param rejects oversized files before they are persisted
- `quota.max-bytes` guards against disk exhaustion across many uploads

### Fixed

- `upload-file-spring-boot-starter` metadata binding: `UploadFileProperties` used flat fields, so the
  documented dotted property names (`merge.fsync`, `cleanup.enabled`, `cleanup.interval`,
  `jdbc.table-name`, `redis.key-prefix`, ...) were silently ignored by Spring Boot's
  `@ConfigurationProperties`. The properties are now grouped into nested `merge` / `cleanup` /
  `async-merge` / `jdbc` / `redis` classes, so the documented names bind as expected (behavior and
  defaults unchanged).

### Compatibility

- All new features are off by default; `security.enabled`, `quota.max-bytes` and
  `cleanup.use-redis-lock` default to off, `observability.log-stats` only logs when a pass actually
  runs
- `UploadTask` gains `schemaVersion` defaulting to `1`; rc.2 JSON remains readable and rollback-safe
- Access-control is purely additive (default `PermitAll`), so existing callers are unaffected
- Redis integration tests are skipped when Docker is unavailable

## [1.0.0-rc.2] - 2026-08-26

**Pre-release.** Second release candidate. New governance/robustness features are off by default, so upgrading from `rc.1` keeps the existing behavior.

### Added

- **Atomic merge (T2)**: merge writes a temp file in the same directory, optionally fsyncs it, then renames it into place with `ATOMIC_MOVE`; a mid-write failure never leaves a corrupt file behind
- **Expired-task cleanup (T1)**: `StorageCleanupService` removes incomplete tasks (and their chunks) idle longer than `cleanup.task-ttl`; `TTL=0` means never clean
- **Orphan-data GC (T3)**: `ChunkStorage.listIdentifiers()` default method; opt-in scan removes chunk/merged dirs with no task record
- **Async merge (T4)**: `submitMerge` / `getMergeStatus`, `action=mergeAsync` (HTTP 202) and `action=mergeStatus`; state machine `NONE -> PENDING -> RUNNING -> SUCCEEDED/FAILED`; new chunks rejected while in flight
- **Pluggable metadata storage (T5)**: new optional modules `upload-file-store-jdbc` (`JdbcTaskStore`) and `upload-file-store-redis` (`RedisTaskStore`); `upload-file.metadata-store` (`auto|memory|file|jdbc|redis`)
- New properties: `merge.fsync`, `merge.atomic`, `cleanup.enabled`, `cleanup.run-on-startup`, `cleanup.interval`, `cleanup.task-ttl`, `cleanup.orphan-enabled`, `async-merge.enabled`, `async-merge.thread-pool-size`, `metadata-store`, `jdbc.table-name`, `jdbc.init-sql`, `redis.host`, `redis.port`, `redis.password`, `redis.key-prefix`, `redis.ttl-seconds`; matching Servlet init-params

### Changed

- `UploadTask` gains `mergeState` / `mergeError` / `mergeStartedAt`; old JSON metadata reads as `NONE` (backward compatible)
- The merge temp file is removed on failure; leftover temp files are reclaimed by the orphan GC (T3)
- `metadata-store=auto` reproduces the rc.1 behavior (file when `metadata-dir` is set, otherwise memory)
- **Merge state is persisted before the chunks are deleted**; if the metadata save fails the chunks stay on disk and the task remains recoverable
- The upload service and the cleanup service share an `IdentifierLock`, so cleanup never races an in-flight upload/merge of the same identifier (double-checked under the lock)
- Later chunks whose declared metadata (`chunkTotal` / `chunkSize` / `fileSize` / `fileName`) disagrees with the first chunk are rejected with `400`

### Fixed

- `FileTaskStore.list()` no longer fails on a leftover `.meta-*.json` temp file or a corrupt metadata file; such records are skipped, matching the JDBC/Redis stores
- `StorageCleanupService` is restartable after `stop()`
- `submitMerge()` rolls the state back to `NONE` when the executor rejects the task, so it is never stuck in a pending merge
- Orphan-data GC is skipped for the in-memory store, preventing all on-disk data from being deleted after a restart
- Merge failure responses no longer leak internal file paths (details are logged server-side)

### Security

- New `max-chunk-size` (Spring Boot) / `chunk.max-size` (Servlet init-param) limit rejects oversized chunks before they are recorded, guarding against disk-exhaustion DoS
- `MemoryTaskStore` now validates identifiers like the other stores (defense-in-depth against path traversal)

### Compatibility

- All new features are off by default (`cleanup.*` and `async-merge.enabled` default to `false`), preserving rc.1 production behavior after a bare upgrade
- Existing endpoints (`merge`, `progress`) and the synchronous `merge()` entry are unchanged; `ChunkStorage` implementations need no change

### Dependencies

- New optional: `upload-file-store-jdbc`, `upload-file-store-redis` (Jedis 4.4.0); H2 2.2.224 (test only)

## [1.0.0-rc.1] - 2026-08-23

**Pre-release.** First release candidate of the initial version. API may still change before `1.0.0`.

### Added

- Chunked upload of large files; only failed chunks are re-transferred
- Resumable (breakpoint) upload: the server records uploaded chunks, clients can pause and resume
- Optional per-chunk MD5 verification
- Chunk merge: ordered merge, final-size validation, automatic chunk cleanup
- Resumable download based on HTTP `Range` (`206 Partial Content`, `416` for unsatisfiable ranges)
- Task metadata persistence: in-memory (`MemoryTaskStore`) or local JSON file (`FileTaskStore`, survives restarts)
- Pluggable storage SPIs: `TaskStore` (metadata) and `ChunkStorage` (chunks)
- Plain Servlet 3.0+ integration: `UploadServlet` / `DownloadServlet`
- Spring Boot 2.x auto-configuration: `upload-file-spring-boot-starter` (zero-config, `@ConditionalOnMissingBean` overridable)
- Demo application with a frontend page: `example/upload-file-demo`
- Bilingual documentation (English / 简体中文)

### Security

- `identifier` and `fileName` are validated to prevent path traversal (including merge-time re-validation)
- Chunks and metadata are written atomically (temp file + rename), avoiding partial files
- Defense-in-depth identifier validation inside `FileTaskStore` / `LocalFileChunkStorage`

### Fixed

- Path traversal via `fileName` when merging chunks
- Concurrent upload race on task creation (striped lock per identifier)
- Checksum mismatch no longer discards the entire upload progress (only the offending chunk is rejected)
- `Content-Length` handling for files below 2 GB on Servlet 3.0 containers (`setContentLength` fallback)

### Changed

- Build targets `--release 8`; producing JDK 8 bytecode requires JDK 9+ to build from source
- Metadata/chunk writes moved from direct writes to atomic temp-file + rename

### Dependencies

- Gson 2.10.1, javax.servlet-api 4.0.1 (provided), Spring Boot 2.7.18 (provided, starter only)
