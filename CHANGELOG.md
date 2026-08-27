# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> 🇨🇳 [简体中文](CHANGELOG.zh-CN.md)

## [Unreleased]

- Planned: Spring Boot 3.x (`jakarta.servlet`) adapter.

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
