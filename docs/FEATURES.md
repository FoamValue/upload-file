# Feature & Functionality List

> 🇨🇳 [简体中文](FEATURES.zh-CN.md)

This document is the complete feature & functionality inventory of **`upload-file` `1.0.0` (GA)** — the
Maven toolkit for large-file chunked upload / resumable (breakpoint) upload / HTTP `Range` resumable
download. It is organized by capability area; the [configuration reference](#13-configuration-reference)
and the [HTTP API](#10-http-integration) tables are the authoritative quick references.

- [1. Core upload](#1-core-upload)
- [2. Chunk merge](#2-chunk-merge)
- [3. Download](#3-download)
- [4. Task lifecycle & cleanup](#4-task-lifecycle--cleanup)
- [5. Storage & metadata](#5-storage--metadata)
- [6. Security](#6-security)
- [7. Quota & limits](#7-quota--limits)
- [8. Concurrency & multi-instance coordination](#8-concurrency--multi-instance-coordination)
- [9. Observability & audit](#9-observability--audit)
- [10. HTTP integration](#10-http-integration)
- [11. Spring Boot integration](#11-spring-boot-integration)
- [12. Extension points (SPIs)](#12-extension-points-spis)
- [13. Configuration reference](#13-configuration-reference)
- [14. Modules & demos](#14-modules--demos)
- [15. Compatibility & guarantees](#15-compatibility--guarantees)

## 1. Core upload

| Feature | Description |
| --- | --- |
| Chunked upload | A large file is split into chunks and uploaded sequentially; only failed chunks are re-transferred (`uploadChunk(request, token, in)`) |
| Resumable upload | The server records uploaded chunks; a client can pause and resume at any time |
| Chunk integrity | Optional per-chunk MD5 verification at write time; `verify-checksum=true` (default) compares the actual MD5 with the declared `chunkMd5` |
| Non-skippable verification | `require-checksum=true` rejects any chunk that arrives without a `chunkMd5` while verification is on (M1) |
| Streamed write with byte cap | Chunk bytes are written through a bounded stream; a chunk exceeding `max-chunk-size` is aborted mid-write, before it lands on disk |
| Chunk reuse | `isChunkUploaded(identifier, chunkIndex)` reports per-index state so a client skips already-uploaded chunks |
| Duplicate-chunk idempotency | Re-uploading an existing chunk index is handled without corrupting stored data |
| Upload progress | `getProgress(identifier)` returns the current `UploadProgress` (uploaded chunk indexes, expected total, etc.) |
| Cross-chunk metadata consistency | Subsequent chunks whose declared metadata disagrees with the first chunk are rejected |
| Declared-count cap | `chunkTotal` is capped (`MAX_CHUNK_TOTAL = 100 000`) as defense against `O(chunkTotal)` CPU work |
| Trusted read API | `getProgressTrusted`, `getTaskTrusted`, `getMergeStatusTrusted` bypass access control for server-side/internal callers |

## 2. Chunk merge

| Feature | Description |
| --- | --- |
| Sequential merge | `merge(identifier)` joins chunks in index order into `mergedFileDir/<identifier>/<fileName>` |
| Missing-chunk guard | Every chunk must be on disk before merging, otherwise `409` `UploadMergeConflictException` is thrown |
| Final size validation | The merged file size is compared with the declared `fileSize`; on mismatch the result is discarded and an error is thrown |
| Atomic merge | Merge to a same-directory temp file, optionally `fsync` (`merge.fsync`), then atomically rename into place; a mid-write failure never leaves a corrupt file |
| Crash-safe ordering | The merged state is persisted **before** chunks are deleted, so an interrupted save never leaves a task with chunks gone and the task un-merged |
| Async merge | `submitMerge(identifier)` returns `202`-style `MergeStatus` (`NONE/PENDING/RUNNING/SUCCEEDED/FAILED`); new chunks are rejected while pending/running/succeeded |
| Merge polling | `getMergeStatus(identifier)` polls async merge state |
| Idempotent submit | Submitting the same identifier while `PENDING`/`RUNNING` returns the current status without re-submitting |
| Merge result | `UploadResult` carries the merged state, final path and final size |
| Post-merge chunk cleanup | Chunks are deleted after a successful merge; leftover chunks are reclaimed by orphan-data cleanup |

## 3. Download

| Feature | Description |
| --- | --- |
| Full download | `GET /download?identifier=xxx` serves the merged file with `200` |
| Range download | HTTP `Range` support returns `206 Partial Content` for single/multi-part ranges |
| Unsatisfiable range | `416` with the documented error body when the range cannot be satisfied |
| Range parsing | `DownloadRange.parse(String)` parses single/multi-part `Range` headers and detects unsatisfiable ranges for reuse outside the HTTP layer |
| File resolution | `resolveFile(identifier)` / `resolveFileName(identifier)` resolve the merged artifact (access-controlled) |
| Range writer | `writeRange(File, start, length, out)` streams an arbitrary byte window of a file |

## 4. Task lifecycle & cleanup

| Feature | Description |
| --- | --- |
| Task record | Each upload is a `UploadTask` in a `TaskStore` (identifier, file metadata, chunk list, merged state, timestamps) |
| Progress query | `getProgress(identifier)` / HTTP `action=progress` |
| Explicit read | `getTask(identifier)` returns the raw task record (stable read for the integration "confirm" phase) |
| Explicit cancel | `cancelUpload(identifier)` / HTTP `action=cancel` reclaims a task's chunks and merged artifact immediately, without waiting for the cleanup scheduler |
| Idempotent cancel | `http.cancel-not-found-status=200` makes canceling a missing task idempotent (default `404`) |
| Expired-task cleanup | `StorageCleanupService` removes incomplete tasks older than `cleanup.task-ttl` on a schedule (`cleanup.enabled`, `cleanup.interval`) |
| Startup pass | `cleanup.run-on-startup=true` runs one cleanup pass at startup |
| Orphan-data GC | `cleanup.orphan-enabled=true` diffs `TaskStore` against disk and removes chunks/files whose metadata is gone |
| Per-entry isolation | Each record is processed in its own try-catch, so one dirty record never aborts the whole cleanup pass |
| Cleanup statistics | Every pass emits structured stats (`CleanupStats`: last run, cleaned tasks, cleaned orphans, elapsed, error) and keeps a queryable snapshot (`getLastStats`) |
| Cleanup hooks | `setErrorListener` / `setStatsListener` expose cleanup events to integrations |
| Redis cleanup lease | `cleanup.use-redis-lock=true` ensures only one instance of a cluster runs the cleanup scheduler |

## 5. Storage & metadata

| Feature | Description |
| --- | --- |
| Pluggable `TaskStore` SPI | `get` / `save` / `remove` contract; ship your own backend or use the built-ins |
| `FileTaskStore` | JSON metadata on disk (`metadata-dir`); survives restarts |
| `MemoryTaskStore` | In-memory metadata; the default when no `metadata-dir` is configured (`metadata-store=memory`) |
| `JdbcTaskStore` | JDBC-backed metadata with automatic table creation (`upload-file-store-jdbc`; `metadata-store=jdbc`) |
| `RedisTaskStore` | Redis-backed metadata with key prefix and record TTL (`upload-file-store-redis`; `metadata-store=redis`) |
| `auto` store selection | `metadata-store=auto` keeps the legacy behavior: file store when `metadata-dir` is set, in-memory otherwise |
| Metadata versioning | `schemaVersion` field so the metadata format can evolve safely; the migrator refuses unsupported versions |
| Task-store migration | `TaskStoreMigrator` copies in-flight tasks between stores (e.g. `FileTaskStore` → JDBC/Redis); never runs automatically, exposed as a bean behind `migration.enabled` |
| Pluggable `ChunkStorage` SPI | Back chunks with the local disk (default) or object storage such as OSS/HDFS |
| Default `LocalFileChunkStorage` | Temp-file + atomic-rename chunk writes |

## 6. Security

| Feature | Description |
| --- | --- |
| Shared-token access control | Optional token check on every entry point (`security.enabled` + `security.token`); the token is accepted in a configurable header (`security.header-name`) or a `token` query param; constant-time comparison |
| Distinguishable decisions | `AccessControl.decide(...)` returns an `AccessDecision` so a denial carries `401` (unauthenticated) or `403` (forbidden) |
| Legacy bridge | The old `check(...)` remains a `@Deprecated` default method bridged through `decide()`, so existing implementations compile and behave unchanged |
| Path-traversal protection | `identifier` and `fileName` are validated (safe characters) and every storage implementation validates internally |
| Download path validation | Download resolution uses canonical-path prefix validation (`getCanonicalFile`) so a merged file can never be resolved outside `mergedFileDir` |
| Size limits | `max-chunk-size`, `max-request-size`, `max-file-size` reject oversized chunks/files before or during landing on disk |
| Actual-byte accounting | Quota and size limits count **received bytes**, never the client-declared `fileSize`/`chunkSize` values |
| Fail-fast unbounded defaults | At least one of `max-chunk-size`, `max-file-size` or `quota.max-bytes` must be configured; startup fails when all three are unbounded |
| Non-skippable checksum | With `verify-checksum` + `require-checksum`, a chunk missing `chunkMd5` is rejected (M1) |
| Log-injection filtering | CR/LF/control characters are stripped from log output before a line is written |
| Merge-conflict protection | `merge()`/`writeMergedFile()` reject tasks whose recorded `chunkTotal` exceeds the cap, even when metadata was written directly into the store |
| Quota enforcement | `507 Insufficient Storage` when the global capacity quota is exceeded; `400` for per-file size violations |

## 7. Quota & limits

| Feature | Description |
| --- | --- |
| Global capacity quota | `quota.max-bytes` caps total used bytes across all tasks (`507` when exceeded) |
| Per-file size limit | `max-file-size` caps a single file's total size (`400` when exceeded) |
| Per-chunk limit | `max-chunk-size` caps one chunk; enforced at the multipart layer and again by the service; `-1` disables |
| Pluggable `QuotaStore` SPI | Default `TaskStoreQuotaStore` recomputes usage from the `TaskStore`; `quota.store=redis` switches to the atomic `RedisQuotaStore` |
| Auto reconciliation | `RedisQuotaStore.reconcile` runs at startup; quota of merged-but-unconfirmed tasks is reclaimed during cleanup |
| Declared-value distrust | All limits are applied to actual received byte counts, so declaring `0`/negative values cannot bypass them |

## 8. Concurrency & multi-instance coordination

| Feature | Description |
| --- | --- |
| Per-identifier locks | Upload, merge and cleanup share the same identifier lock, so background GC never races live data |
| Lock providers | `lock.identifier-lock`: `local` (in-process), `redis` (distributed); a striped in-process provider is also shipped |
| Lease TTL | Distributed identifier locks carry a TTL (`lock.ttl`) so a crashed holder auto-expires |
| Lease renewal | A held distributed lock is renewed while held (`lock.renew-interval`; `0` derives `ttl/3`), so a critical section longer than the TTL (e.g. a large merge on a slow disk) keeps mutual exclusion |
| Acquire timeout | `lock.acquire-timeout` bounds how long to wait for a distributed lock |
| Redis cleanup lease | Only one cluster instance runs the cleanup scheduler when `cleanup.use-redis-lock=true` |
| Async-merge executor | `async-merge.enabled` + `async-merge.thread-pool-size` run merges in a bounded background pool |

## 9. Observability & audit

| Feature | Description |
| --- | --- |
| Cleanup stats log | `observability.log-stats=true` (default) logs one structured line per cleanup pass |
| Cleanup snapshot | `CleanupStats` snapshot queryable via `StorageCleanupService.getLastStats()` |
| Access log | `observability.access-log=true` emits one structured access-decision line per entry-point check |
| Access-log scope | `observability.access-log-scope`: `task` (default: deny + task-level allow events, skipping per-chunk upload noise) · `deny` (denials only) · `all` (every decision) |
| Audit hooks | `AccessControlListener` fires on every entry point (allow/deny + decision time); the MVC and Servlet paths share the same listener wiring |
| Audit context | `AccessContext` / `AccessContextHolder` carry method, URI, IP and User-Agent through the decision path; the listener's 6-arg overload receives them (5-arg legacy stays source-compatible) |
| Log hygiene | Sanitized IP/User-Agent values are always written to the access log |

## 10. HTTP integration

| Feature | Description |
| --- | --- |
| Upload servlet | `POST /upload` (multipart, file field `file`) with params `identifier`, `fileName`, `fileSize`, `chunkSize`, `chunkTotal`, `chunkIndex`, `chunkMd5`; returns progress JSON |
| Progress query | `GET /upload?action=progress&identifier=xxx` |
| Merge | `POST /upload?action=merge&identifier=xxx` |
| Async merge | `POST /upload?action=mergeAsync&identifier=xxx` (`202`); `GET /upload?action=mergeStatus&identifier=xxx` |
| Cancel | `POST /upload?action=cancel&identifier=xxx` — reclaims chunks and merged artifact |
| Download servlet | `GET /download?identifier=xxx` (`200`); with a `Range` header (`206` / `416`) |
| Endpoint control | `endpoint.enabled=false` is a beans-only mode; `endpoint.upload-enabled` / `endpoint.download-enabled` toggle each servlet; `/download` is **off by default** (minimal exposure) |
| Bean overridability | Both the servlet instances and their registrations can be overridden by host beans |
| Stable error mapping | `UploadErrorCode` → stable HTTP status: `400` invalid/oversized/MD5 mismatch · `401` access denied · `403` forbidden · `404` not found · `409` merge conflict · `416` unsatisfiable range · `507` quota exceeded |
| Symbolic error codes | Every coded exception carries a stable symbol (`UPLOAD_VALIDATION`, `UPLOAD_CHECKSUM`, `UPLOAD_NOT_FOUND`, `UPLOAD_MERGE_CONFLICT`, `ACCESS_DENIED`, `QUOTA_EXCEEDED`, `MISSING_ACTION`, `UPLOAD_UNKNOWN_ACTION`, `MISSING_IDENTIFIER`, `UPLOAD_SERVER_ERROR`, `RANGE_NOT_SATISFIABLE`) |
| Error body modes | `http.error-body=legacy` (default, per-endpoint models) or `standard` (uniform `UploadHttpError` body) |
| Error rendering SPI | `UploadErrorRenderer` lets a host provide its own failure body (consumed by the starter) |
| Multipart strategy | `multipart.strategy`: `component` (limits mapped into `@MultipartConfig`) · `spring` (follow `spring.servlet.multipart.*`) · `unlimited` (no container cap; service still enforces its own limits) |

## 11. Spring Boot integration

| Feature | Description |
| --- | --- |
| Zero-config auto-configuration | `upload-file-spring-boot-starter` (Boot 2.x, `javax`) and `upload-file-spring-boot-starter-jakarta` (Boot 3/4, `jakarta`) wire the full stack from `upload-file.*` properties |
| Service beans | `ResumableUploadService`, `ResumableDownloadService`, `StorageCleanupService` are auto-configured and consumable as beans |
| Trusted read facade | `TrustedUploadService` is auto-exposed for server-side reads; disable with `trusted-upload-service.enabled=false` or override the bean |
| Endpoint registration | Upload/download servlets are registered as `ServletRegistrationBean`s and can be replaced by host beans |
| Starter wiring guardrails | Secure defaults and wiring conflicts are surfaced at startup |
| BOM | `upload-file-bom` aligns the versions of all 7 library modules; a host imports the BOM and writes `artifactId` only |
| Demo profiles | The Boot 4 demo runs with `jdbc` (embedded H2) and `redis` profiles for optional metadata stores |

## 12. Extension points (SPIs)

| SPI | Contract | Built-in implementations |
| --- | --- | --- |
| `TaskStore` | `get` / `save` / `remove` task records | `FileTaskStore`, `MemoryTaskStore`, `JdbcTaskStore`, `RedisTaskStore` |
| `ChunkStorage` | chunk save/exists/delete (byte-bounded 4-arg `default` overload) | `LocalFileChunkStorage` |
| `QuotaStore` | quota consume/release + `reconcile` (default no-op) | `TaskStoreQuotaStore`, `RedisQuotaStore` |
| `AccessControl` | `decide(...) → AccessDecision` (legacy `check(...)` default bridge) | `PermitAllAccessControl`, `TokenAccessControl`, `AbstractAccessControl` |
| `AccessControlListener` | allow/deny events + decision time (6-arg overload, 5-arg legacy) | starter/servlet wire the access-log listener |
| `IdentifierLockProvider` | `lock(identifier) → IdentifierLockHandle` | `IdentifierLock`, `StripedIdentifierLockProvider`, `RedisIdentifierLockProvider` |
| `UploadErrorRenderer` | failure-body rendering | `UploadErrorRenderers` |

## 13. Configuration reference

Prefix `upload-file`. Values shown are defaults.

| Property | Default | Description |
| --- | --- | --- |
| `storage-dir` | `./upload-file-data` | Root dir for chunks and merged files |
| `metadata-dir` | *(empty)* | Task metadata persistence dir; empty = in-memory (`MemoryTaskStore`) |
| `verify-checksum` | `true` | Verify each chunk MD5 |
| `require-checksum` | `false` | With `verify-checksum`, reject a chunk whose `chunkMd5` is missing |
| `upload-url` | `/upload` | Upload servlet mapping |
| `download-url` | `/download` | Download servlet mapping |
| `max-chunk-size` | `10 MB` | Max bytes per chunk; `-1` disables the chunk limit |
| `max-request-size` | `-1` | Max multipart request size; `-1` unlimited |
| `max-file-size` | `-1` | Max total size of a single file; `-1` unlimited |
| `metadata-store` | `auto` | `auto` · `memory` · `file` · `jdbc` · `redis` |
| `merge.fsync` | `true` | fsync the merged temp file before rename |
| `merge.atomic` | `true` | Temp-file + atomic-move merge |
| `cleanup.enabled` | `false` | Start the expired-task/orphan cleanup scheduler |
| `cleanup.run-on-startup` | `false` | Run one cleanup pass at startup |
| `cleanup.interval` | `1h` | Cleanup period |
| `cleanup.task-ttl` | `24h` | Expiry of incomplete tasks; `0`/negative = never |
| `cleanup.orphan-enabled` | `false` | Enable orphan-data GC |
| `cleanup.use-redis-lock` | `false` | Only one instance runs cleanup at a time |
| `async-merge.enabled` | `false` | Enable async merge |
| `async-merge.thread-pool-size` | `2` | Async merge thread count |
| `endpoint.enabled` | `true` | Master switch; `false` = beans-only mode |
| `endpoint.upload-enabled` | `true` | Register the upload servlet |
| `endpoint.download-enabled` | `false` | Register the download servlet (off by default) |
| `http.error-body` | `legacy` | `legacy` per-endpoint models or `standard` uniform body |
| `http.cancel-not-found-status` | `404` | Status for canceling a missing task; `200` = idempotent |
| `multipart.strategy` | `component` | `component` · `spring` · `unlimited` |
| `security.enabled` | `false` | Master switch for access control |
| `security.token` | *(empty)* | Shared access token |
| `security.header-name` | `X-Access-Token` | Token header name (`token` query param also accepted) |
| `quota.max-bytes` | `0` | Global capacity quota; `0`/negative = off |
| `quota.store` | `task-store` | `task-store` (recompute from TaskStore) or `redis` (atomic) |
| `observability.log-stats` | `true` | Log structured cleanup stats per pass |
| `observability.access-log` | `false` | Log structured access decisions |
| `observability.access-log-scope` | `task` | `task` · `deny` · `all` |
| `migration.enabled` | `false` | Expose the `TaskStoreMigrator` bean (never auto-runs) |
| `lock.identifier-lock` | `local` | `local` (in-process) or `redis` (distributed) |
| `lock.acquire-timeout` | `10s` | Max wait for a distributed identifier lock |
| `lock.ttl` | `30s` | Distributed lock lease TTL |
| `lock.renew-interval` | `0` (`ttl/3`) | Lease renewal interval while a lock is held |
| `jdbc.table-name` | `upload_task` | JDBC table name |
| `jdbc.init-sql` | *(auto-create DDL)* | SQL to create the table (`%s` = table name) |
| `redis.host` | `localhost` | Redis host |
| `redis.port` | `6379` | Redis port |
| `redis.password` | *(empty)* | Redis password (empty = no auth) |
| `redis.key-prefix` | `upload:task:` | Redis key prefix |
| `redis.ttl-seconds` | `0` | Redis record TTL; `0` = no expiry |

## 14. Modules & demos

| Module | Purpose |
| --- | --- |
| `upload-file-core` | Pure-Java components: models, validation, storage SPIs, upload/download/cleanup services |
| `upload-file-servlet` | Servlet 3.0+ (`javax.servlet`) wiring: upload servlet, download servlet |
| `upload-file-servlet-jakarta` | Jakarta Servlet 5/6 twin (identical FQCNs) |
| `upload-file-spring-boot-starter` | Spring Boot 2.x auto-configuration (`javax`) |
| `upload-file-spring-boot-starter-jakarta` | Spring Boot 3/4 auto-configuration (`jakarta`) |
| `upload-file-store-jdbc` | Optional JDBC `TaskStore` (auto table creation) |
| `upload-file-store-redis` | Optional Redis `TaskStore` (Jedis) |
| `upload-file-bom` | Version alignment for all library modules |
| `example/upload-file-demo` | Spring Boot 2 demo (chunked upload UI) |
| `example/upload-file-boot4-demo` | Spring Boot 4 demo with `jdbc`/`redis` metadata-store profiles |
| `example/upload-file-servlet-demo` | Plain-Servlet demo via `web.xml` (Jetty) |

## 15. Compatibility & guarantees

| Item | Guarantee |
| --- | --- |
| Runtime | JDK 8+ bytecode (`--release 8`); JDK 8 can consume the artifacts directly |
| Dependencies | Gson only (core module) |
| Servlet lines | `javax` (Servlet 3/4, Boot 2) and `jakarta` (Servlet 5/6, Boot 3/4) twins with identical FQCNs and `upload-file.*` properties |
| Versioning | Semantic versioning from `1.0.0`; breaking changes may only land in `2.0.0` |
| GA lines | `1.0.0.x` receives security fixes; the javax line is maintenance-only and converges to a single jakarta line in `2.0.0` |
| Binary compatibility | Enforced by a Revapi gate in CI (verified before each release) |

> See also: [Design](DESIGN.md) · [HTTP API reference](API.md) · [Future Optimization Directions](ROADMAP.md) · [Changelog](../CHANGELOG.md)
