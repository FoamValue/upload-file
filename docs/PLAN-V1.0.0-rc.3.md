# V1.0.0-rc.3 Task Plan (Production Readiness Hardening)

> 🇨🇳 [简体中文](PLAN-V1.0.0-rc.3.zh-CN.md)
>
> The final hardening release before the `1.0.0` GA. rc.3 adds no new marketing capabilities; it closes the
> five production-readiness gaps (access control / multi-instance / quota / observability / migration) left
> over from the rc.1 → rc.2 analysis ([ANALYSIS](ANALYSIS-V1.0.0-rc.2.md)) and freezes the scope and API
> contract for the 1.0.0 GA.
>
> ⏳ **Status: planned, not yet implemented.**

## 1. Goals & Scope

rc.2 completed the P0 items (T1–T5); the feature chain is self-consistent and tested, but the following gaps
remain for a "1.0.0 production release" (from the product review + open items in
[ANALYSIS](ANALYSIS-V1.0.0-rc.2.md)):

| # | Gap | Description | rc.3 task |
| --- | --- | --- | --- |
| G1 | No access control | Upload/download endpoints are fully open; `identifier` is the only key | T6 |
| G2 | Multi-instance/HA is overstated | No scheduling lock; chunks on local disks are not shared | T7 |
| G3 | No size/quota limits | Only `max-chunk-size`; no total file size or capacity quota | T8 |
| G4 | No observability | Cleanup logs only; no stats/metrics | T9 |
| G5 | No migration tool | Switching FileTaskStore → JDBC/Redis drops in-flight tasks | T10 |
| G6 | No metadata version | No `schemaVersion` for metadata format evolution | T11 |
| G7 | No compat regression | Missing rc.1/rc.2 data + default-config regression and C1/C2 cases | T12 |
| G8 | No 1.0.0 scope definition | No SOW; no API-freeze declaration | T13 |

## 2. Architecture & Constraints

Keeps the existing modular layering, core stays framework-free (JDK 8), new features are off by default:

```
upload-file (parent POM / aggregator)
├── upload-file-core                        # pure Java, T6~T11 core logic
├── upload-file-servlet                     # Servlet wiring, new init-params
├── upload-file-spring-boot-starter         # auto-config, new upload-file.* properties
├── upload-file-store-jdbc   [optional]     # migration target
├── upload-file-store-redis  [optional]     # migration target / scheduling-lock backend
└── example/…                               # demos updated accordingly
```

Constraints:
- All business logic lives in core; servlet / starter only assemble and map config;
- New components follow `@ConditionalOnMissingBean` / init-param override patterns;
- **Every new capability is off by default or keeps the old behavior**; upgrading rc.2 → rc.3 is a
  zero-behavior-change release;
- rc.3 is the last release candidate before 1.0.0: **the API is frozen afterwards** — any SPI change in rc.3
  must be assessed for breakage and recorded.

## 3. Task Breakdown

### T6 Optional Access Control (G1)

- **Files**: new `AccessControl` SPI in core (`check(identifier, action)`); built-in `PermitAllAccessControl`
  and `TokenAccessControl` (checks a shared token in header/query param); invoked at entry points of
  `ResumableUploadService` / `ResumableDownloadService`; `UploadServlet` / `DownloadServlet` parse the token.
- **Approach**: validation is enabled only when `security.token` is non-empty; `PermitAll` is the default.
  Tokens are compared in constant time with `MessageDigest.isEqual` to avoid timing attacks.
- **Config**: `upload-file.security.enabled`, `upload-file.security.token`, `upload-file.security.header-name`
  (see section 4).
- **Acceptance**: without a token, behavior is byte-identical to rc.2; with a token, upload/progress/merge/download
  return `401` when missing or wrong; header and query-param transport both work; unit tests cover 401/200 and the
  constant-time comparison.
- **Estimate**: 1 person-day.

### T7 Multi-Instance Deployment Constraints & Optional Scheduling Lock (G2)

- **Files**: `StorageCleanupService` (optional Redis scheduling lock), docs deployment-constraints page, `README`.
- **Approach**:
  - **Documented (required)**: define "single-instance / shared disk" as the default supported topology; when
    `metadata-store=jdbc|redis` and chunks are still on local disks, multi-instance is supported only on shared
    disks, and the docs note that horizontal scaling requires object storage.
  - **Optional scheduling lock (off by default)**: with `cleanup.use-redis-lock=true`, the cleanup schedule takes a
    Redis `SET NX EX` lease so multiple instances do not run duplicate cleanup/GC. Depends only on the existing
    `upload-file-store-redis` module.
- **Config**: `upload-file.cleanup.use-redis-lock` (default `false`).
- **Acceptance**: off by default with no scheduling change; with it on, two instances running at once let only one
  clean; a lock failure skips that round with a warning and never blocks business traffic.
- **Estimate**: 1.5 person-days.

### T8 File Size Limit & Capacity Quota (G3)

- **Files**: `ResumableUploadService` (validated before merge and before chunk persistence), `ChunkUploadRequest`
  validation, starter/servlet config.
- **Approach**:
  - **Per-file total size limit**: `max-file-size`, checked both when the first chunk registers `fileSize` and before
    merge; rejects over-limit requests with `400`;
  - **Optional global capacity quota** (off by default): `quota.max-bytes`, estimated from the sum of merged file
    sizes in `TaskStore` plus the current task's declared `fileSize`; checked before upload and merge, rejecting
    over-quota with `507 Insufficient Storage`.
- **Config**: `upload-file.max-file-size` (default `-1`), `upload-file.quota.max-bytes` (default `-1`).
- **Acceptance**: an over-limit file is rejected at the first chunk and nothing is persisted; merge is rejected when
  the cumulative size exceeds the quota; `-1` keeps rc.2 behavior.
- **Estimate**: 1 person-day.

### T9 Minimal Observability (G4)

- **Files**: `StorageCleanupService` gains `CleanupStats` (last-run time, cleaned task/chunk/orphan counts, elapsed
  time, errors); `UploadServlet` / `DownloadServlet` optional request counters; starter structured logging.
- **Approach**: `CleanupStats` is exposed as a queryable bean; each cleanup pass writes one structured log line
  `upload-file cleanup: {run, cleanedTasks, cleanedOrphans, elapsedMs, error}`.
- **Config**: `upload-file.observability.log-stats` (default `true`).
- **Acceptance**: with cleanup enabled the log contains the stats line; the `CleanupStats` bean is injectable; the
  switch off adds no extra logging.
- **Estimate**: 1 person-day.

### T10 Metadata Migration Tool (G5)

- **Files**: new `TaskStoreMigrator` in core (generic over source/target `TaskStore`); `upload-file-store-jdbc` /
  `upload-file-store-redis` provide a `main` or factory method; starter exposes a migration bean
  (`@ConditionalOnProperty`).
- **Approach**: iterate `TaskStore.list()`, `get` → `save` into the target; in-flight tasks are preserved as-is
  (including `uploadedChunks` and merge state); supports `FileTaskStore → JdbcTaskStore` and
  `FileTaskStore → RedisTaskStore`; checks `schemaVersion` compatibility before migrating. Provides a CLI entry
  point and a programmatic API; **it never runs automatically**.
- **Config**: `upload-file.migration.enabled` (default `false`) + target selection reuses `metadata-store`.
- **Acceptance**: a source with both in-flight and completed tasks migrates so the target's `get/list` match the
  source; a failed migration is re-entrant (idempotent).
- **Estimate**: 1 person-day.

### T11 Metadata Format Version (G6)

- **Files**: `UploadTask` gains a `schemaVersion` field; `FileTaskStore` read/write; load-time fallback.
- **Approach**: the current format is `schemaVersion=1`; old JSON without the field is treated as `1`; any future
  format change must bump the version and provide a migration path (with T10).
- **Acceptance**: JSON produced by rc.2 reads back with `schemaVersion=1`; writes include the field; older versions
  reading the new JSON are unaffected (Gson ignores unknown fields).
- **Estimate**: 0.5 person-days.

### T12 Compat Regression Suite & Upgrade/Rollback Matrix (G7)

- **Files**: a new `compat` test source set (src/test or a dedicated module); docs upgrade/rollback matrix.
- **Approach**:
  - Boot with "rc.2 JSON metadata samples + rc.2 directory layout (chunks/, files/)" and verify progress/merge/download
    behave identically under the **default config**;
  - Regression cases for the two high-risk combinations: C1 (TTL silently deletes long-idle tasks) and C2
    (in-memory store + orphan GC deletes everything on startup);
  - Assert that every new switch in T6–T11 in its "off" state starts no threads and changes no existing path;
  - Docs add an rc.1 → rc.2 → rc.3 upgrade and rollback matrix (config list, default-value changes, rollback notes).
- **Acceptance**: `mvn verify` includes the compat suite and is green; the matrix covers the defaults and rollback
  impact of G1–G8.
- **Estimate**: 1 person-day.

### T13 V1.0.0 Scope Definition (SOW) (G8)

- **Files**: new `docs/PLAN-V1.0.0.md` (bilingual); README updated before release.
- **Approach**: define the 1.0.0 GA scope:
  - **Included**: all of T1–T13 (cleanup / atomic merge / async merge / JDBC·Redis stores / access control / size
    quota / migration / observability);
  - **Explicitly excluded and deferred**: instant upload, whole-file integrity verification (SHA-256), dedup, tus,
    multi-language SDKs, object-storage backends, recycle bin, audit logs, virus scanning, presigned sharing,
    versioning, encryption, compression, preview/transcoding, webhooks;
  - **Production constraints**: single-instance / shared disk by default; when no built-in auth is enabled, a
    gateway/reverse proxy must enforce it; local-disk chunks do not support cross-node merge;
  - **API freeze**: no breaking changes after rc.3; `1.0.0` follows semantic versioning.
- **Acceptance**: the SOW is reviewed and serves as the basis of the 1.0.0 release announcement.
- **Estimate**: 0.5 person-days.

## 4. New Configuration Properties (prefix `upload-file`)

| Property | Default | Description | Task |
| --- | --- | --- | --- |
| `security.enabled` | `false` | Enable access-control checks | T6 |
| `security.token` | *(empty)* | Shared token; empty = no checks | T6 |
| `security.header-name` | `X-Access-Token` | Token header name (same-named query param also accepted) | T6 |
| `max-file-size` | `-1` | Per-file total size limit (bytes), -1 = unlimited | T8 |
| `quota.max-bytes` | `-1` | Global capacity quota (bytes), -1 = off | T8 |
| `cleanup.use-redis-lock` | `false` | Use a Redis lease lock for multi-instance cleanup scheduling | T7 |
| `observability.log-stats` | `true` | Structured cleanup-stats logging | T9 |
| `migration.enabled` | `false` | Expose the migration bean (never runs automatically) | T10 |

Plain Servlet deployments configure the same options as init-params (`UploadFileContext` parses them, named as
above, e.g. `security.token`, `max-file-size`, `cleanup.use-redis-lock`).

## 5. Compatibility Notes

- T6–T11 are all off/empty/`-1` by default; upgrading rc.2 → rc.3 is a zero-behavior-change release (guaranteed by
  the T12 compat suite);
- `UploadTask` gains `schemaVersion` defaulting to `1`; old JSON remains readable/writable and rollback-safe;
- The T7 lock affects only "enabled + multi-instance"; T10 migration is an explicit tool that never runs
  automatically;
- The API freezes at 1.0.0: rc.3 SPI additions (e.g. `AccessControl`) are purely additive and do not break existing
  custom implementations.

## 6. Test Plan

- Unit tests keep JUnit 4 + JaCoCo; core line coverage stays at or above the current level; new coverage: 401 auth,
  size/quota rejection, `CleanupStats`, migration idempotency, `schemaVersion` fallback;
- New `compat` regression suite (rc.2 data samples + default config + C1/C2 high-risk cases), included in
  `mvn verify`;
- `example/upload-file-demo` gains security/quota config samples and a manual frontend walkthrough;
- Before release run `mvn verify` (gpg only in the release profile) and `mvn jacoco:report`.

## 7. Docs & Example Updates

- `README(.md)`: new properties, access-control usage, multi-instance constraints, migration tool usage;
- `docs/API(.md)`: `401`, `400`, and `507` responses;
- `docs/DESIGN(.md)`: new `AccessControl`, `CleanupStats`, `TaskStoreMigrator` component responsibilities;
- `docs/ROADMAP(.md)`: mark the rc.3 plan and the 1.0.0 scope;
- **New** `docs/PLAN-V1.0.0.md` (SOW, T13) and the upgrade/rollback matrix (T12);
- Demo `application.yml` / servlet `web.xml` gain the new config samples.

## 8. Milestones & Release

1. **M1**: T6 access control + T8 size/quota (entry protection first);
2. **M2**: T10 migration tool + T11 `schemaVersion` (data layer complete);
3. **M3**: T9 observability + T7 multi-instance constraints (ops loop closed);
4. **M4**: T12 compat regression + T13 SOW (quality and release basis);
5. **M5**: `1.0.0-rc.3 → 1.0.0`, publish to Maven Central with the existing release profile; the announcement
   references the SOW and the upgrade/rollback matrix.
