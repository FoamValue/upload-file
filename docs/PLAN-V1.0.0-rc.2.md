# V1.0.0-rc.2 Task Development Plan

> 🇨🇳 [简体中文](PLAN-V1.0.0-rc.2.zh-CN.md)
>
> Corresponds to the P0 items in [Future Optimization Directions](ROADMAP.md) ("Complete Optimization List"), targeting version **V1.0.0-rc.2**.
>
> ✅ **Status: all 5 P0 items (T1–T5) are implemented and covered by tests in `1.0.0-rc.2`.** In addition to the plan, the release ships robustness hardening: merge state persisted before chunk cleanup, a shared `IdentifierLock` between upload and cleanup, cross-chunk metadata consistency checks, a chunk size cap, and store-side identifier validation in `MemoryTaskStore`.

## 1. Goal & Scope

V1.0.0-rc.2 implements the 5 P0 items (foundational robustness) of the current plan; P1/P2 items are scheduled for later versions:

| ID | P0 item | Description |
| --- | --- | --- |
| T1 | Expired-task cleanup (TTL/GC) | scheduled expiry cleanup of incomplete tasks and chunks |
| T2 | Atomic merge | merge to a temp file then rename on success; no corrupt files |
| T3 | Orphan data GC | diff metadata vs disk; clean orphan chunks/files |
| T4 | Async merge | run merge in the background so HTTP requests are not blocked |
| T5 | Pluggable metadata storage | add JDBC / Redis TaskStore as optional modules |

## 2. Architecture & Constraints (aligned with the plugin-style project)

Follow the existing layered modules and keep core free of framework dependencies (JDK 8):

```
upload-file (parent POM / aggregator)
├── upload-file-core                        # pure Java, implements T1-T4 core logic
├── upload-file-servlet                     # Servlet integration; new actions and init-param wiring
├── upload-file-spring-boot-starter         # auto-configuration; new upload-file.* properties
├── upload-file-store-jdbc   [new module]   # optional: JDBC TaskStore plugin
├── upload-file-store-redis  [new module]   # optional: Redis TaskStore plugin
└── example/…                               # demos updated in sync
```

Constraints:
- All business logic goes into core; servlet/starter only do wiring and config mapping;
- New components follow the `@ConditionalOnMissingBean` / init-param override pattern;
- Default behavior and existing APIs stay backward compatible; new features default off or preserve old behavior;
- Version stays at 1.0.0-rc.x, so small SPI changes (e.g. a default method on `ChunkStorage`) are allowed.

## 3. Task Breakdown

### T1 Expired-task cleanup (TTL/GC)

- **Files**: new `StorageCleanupService` in core; `UploadTask` (reuse `updateTime`); `FileTaskStore.list()`.
- **Approach**: judge by `UploadTask.updateTime`; incomplete tasks older than the TTL are removed together with their chunks via `ChunkStorage.deleteChunks`. Scheduling uses the JDK `ScheduledExecutorService`; core exposes `start/stop/cleanup()` lifecycle methods.
- **Config**: `upload-file.cleanup.enabled`, `cleanup.interval`, `cleanup.task-ttl` (see section 4).
- **Acceptance**: expired tasks/chunks removed; fresh tasks kept; TTL=0 means never clean; no busy-loop when no tasks.
- **Estimate**: 1 person-day.

### T2 Atomic merge

- **Files**: `ResumableUploadService.merge()` (core).
- **Approach**: merge into a temp file in the same directory (`<fileName>.merge-<uuid>.tmp`), `FileChannel.force` (optional fsync), then `ATOMIC_MOVE` to the final file; delete the temp file on any exception; leftover temp files under `files/<id>/` are picked up by the T3 orphan cleanup.
- **Config**: `upload-file.merge.fsync` (default true).
- **Acceptance**: on a mid-write failure the final file does not exist and the temp file is removed; metadata is updated only after a successful merge; existing merge tests stay green.
- **Estimate**: 0.5-1 person-day.

### T3 Orphan data GC

- **Files**: `StorageCleanupService` in core (shares the scheduler with T1); `ChunkStorage` gains a default `listIdentifiers()` (overridden by `LocalFileChunkStorage` to list directories); compare against the merged-file dir.
- **Approach**: periodic + startup scan: under the chunk root and the merged-file dir, directories not present in `TaskStore.list()` are treated as orphans and deleted.
- **Config**: `upload-file.cleanup.orphan-enabled`, `cleanup.run-on-startup`.
- **Acceptance**: crafted orphan chunk/merged dirs are removed by a cleanup run; data with task records is untouched; custom `ChunkStorage` implementations that do not override `listIdentifiers()` return an empty set and nothing is wrongly deleted.
- **Estimate**: 1 person-day.

### T4 Async merge

- **Files**: `ResumableUploadService` (add `submitMerge`/`getMergeStatus` and an optional `ExecutorService`); `UploadTask` gains `mergeState/mergeError/mergeStartedAt`; `UploadServlet` adds `action=mergeAsync` and `action=mergeStatus`; starter wires a thread pool.
- **Approach**: merge state machine `NONE -> PENDING -> RUNNING -> SUCCEEDED/FAILED`; async merge holds the same per-identifier striped lock; the synchronous `merge()` entry is kept (default); when async is enabled, new chunk uploads are rejected while RUNNING/SUCCEEDED. Old JSON metadata missing the new fields is treated as `NONE`.
- **Config**: `upload-file.async-merge.enabled`, `async-merge.thread-pool-size`.
- **Acceptance**: async submit returns 202 + status; polling reaches a terminal state; failure state carries the error message; behavior matches the old version when disabled; concurrent submits of the same identifier are idempotent.
- **Estimate**: 2 person-days.

### T5 Pluggable metadata storage

- **New modules**:
  - `upload-file-store-jdbc`: `JdbcTaskStore implements TaskStore` on a table `upload_task(identifier, data, create_time, update_time)` with JSON serialization (reusing Gson); `initSql` supports auto table creation; H2 used in tests.
  - `upload-file-store-redis`: `RedisTaskStore implements TaskStore` based on Jedis (core does not depend on third-party frameworks; the Redis client dependency stays in this module).
- **Starter wiring**: new `upload-file.metadata-store` (`auto|memory|file|jdbc|redis`, default `auto` keeps old behavior: file when `metadata-dir` is set, otherwise memory); jdbc/redis are wired via conditional beans and fall back with a warning when the dependency or DataSource is missing.
- **Config**: `metadata-store`, `jdbc.table-name`, `redis.key-prefix`, `redis.ttl-seconds`.
- **Acceptance**: `JdbcTaskStore` CRUD/list passes (H2 unit tests); the Redis module ships a smoke test or documented manual verification; the starter still works with the default storage when only core+starter are on the classpath.
- **Estimate**: 3 person-days.

## 4. New Configuration Properties (prefix `upload-file`)

| Property | Default | Description | Task |
| --- | --- | --- | --- |
| `merge.fsync` | `true` | fsync before rename to ensure data is persisted | T2 |
| `cleanup.enabled` | `true` | enable TTL/orphan cleanup scheduling | T1/T3 |
| `cleanup.run-on-startup` | `true` | run one scan at startup | T3 |
| `cleanup.interval` | `1h` | cleanup period | T1/T3 |
| `cleanup.task-ttl` | `24h` | expiry of incomplete tasks; 0 = never | T1 |
| `cleanup.orphan-enabled` | `true` | enable orphan data cleanup | T3 |
| `async-merge.enabled` | `false` | enable async merge | T4 |
| `async-merge.thread-pool-size` | `2` | async merge thread count | T4 |
| `metadata-store` | `auto` | metadata store type: auto/memory/file/jdbc/redis | T5 |
| `jdbc.table-name` | `upload_task` | JDBC table name | T5 |
| `redis.key-prefix` | `upload:task:` | Redis key prefix | T5 |
| `redis.ttl-seconds` | `0` | Redis record TTL; 0 = none | T5 |

For pure Servlet deployments, matching init-params are added (parsed by `UploadFileContext`, named the same as above).

## 5. Compatibility

- New `UploadTask` fields deserialize to null via Gson; defaults are filled on load (following the existing `uploadedChunks` handling), so old JSON metadata still reads correctly;
- `ChunkStorage` gains a `listIdentifiers()` default method returning an empty set; existing custom implementations need no change;
- The synchronous `merge()` entry is kept; async is an opt-in option, off by default, so existing callers are unaffected;
- `metadata-store=auto` exactly reproduces current behavior (file when `metadata-dir` is set, otherwise memory).

## 6. Test Plan

- Unit tests keep JUnit 4 + JaCoCo; core line-coverage target stays at or above the current 88.7%; new logic covers: merge failure rollback, TTL boundaries, orphan cleanup, async state machine, `JdbcTaskStore` CRUD;
- `example/upload-file-demo` gains an `application.yml` example config and the front-end upload flow is verified manually;
- Before release run `mvn verify` (gpg only in the release profile) and `mvn jacoco:report` to check coverage.

## 7. Docs & Example Updates

- `README(.zh-CN).md`: new properties, new modules, async-merge usage;
- `docs/API(.zh-CN).md`: `mergeAsync` / `mergeStatus` endpoint docs;
- `docs/DESIGN(.zh-CN).md`: update component responsibilities and directory layout (temp files and cleanup);
- `docs/ROADMAP(.zh-CN).md`: mark P0 planned release version as V1.0.0-rc.2;
- Example `application.yml` / servlet `web.xml`: add new config examples.

## 8. Milestones & Release

1. **M1**: T2 atomic merge (smallest change; stabilize merge first);
2. **M2**: T1+T3 cleanup/governance (share `StorageCleanupService` and the scheduler);
3. **M3**: T4 async merge (after merge is stabilized);
4. **M4**: T5 pluggable metadata storage (new modules + starter wiring; can run in parallel with M2/M3);
5. **M5**: docs/examples/tests completed, version `1.0.0-rc.2 -> 1.0.0`, published to Maven Central via the existing release profile.
