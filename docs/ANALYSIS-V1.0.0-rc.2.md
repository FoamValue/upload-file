# V1.0.0-rc.2 New Features Analysis & rc.1 Compatibility Assessment

> Based on the [V1.0.0-rc.2 Task Development Plan](PLAN-V1.0.0-rc.2.md) and the current `1.0.0-rc.1` code baseline.
> Purpose: assess upgrade conflicts, compatibility strategy, and gaps that must be considered when `rc.1` is already running at scale.
>
> ✅ **Status: the recommended defaults and guardrails below are implemented in `1.0.0-rc.2`** — `cleanup.*`/`async-merge.*` default off, `merge.atomic` fallback switch present, orphan GC skipped for the in-memory store, and a shared `IdentifierLock` coordinates cleanup with in-flight uploads/merges.

## 0. rc.1 Baseline (basis of the analysis)

What rc.1 ships today (per the code):

- Chunk upload / progress query / merge; `UploadServlet` exposes `POST /upload` (chunk), `action=merge`, `action=progress` (`upload-file-servlet/.../UploadServlet.java:68-131`);
- Resumable download: `ResumableDownloadService` resolves files via `TaskStore` + `mergedFileDir` (`upload-file-core/.../ResumableDownloadService.java:41-64`);
- Metadata store SPI: `TaskStore` (`get/save/remove/list`), built-in `MemoryTaskStore` and `FileTaskStore` (one JSON per task, temp-file + atomic rename, with an in-process `cache`);
- Chunk storage SPI: `ChunkStorage`, built-in `LocalFileChunkStorage` (`<root>/<identifier>/<chunkIndex>.part`);
- Merge: `ResumableUploadService.merge()` writes directly to `files/<identifier>/<fileName>`, **non-atomic**; a mid-write failure can leave a corrupt file (`ResumableUploadService.java:154-203`);
- `UploadTask` fields: `identifier/fileName/fileSize/chunkSize/chunkTotal/uploadedChunks/merged/finalPath/finalFileSize/createTime/updateTime` (`UploadTask.java:15-30`);
- Starter config (prefix `upload-file`): `storageDir/metadataDir/verifyChecksum/uploadUrl/downloadUrl/maxChunkSize/maxRequestSize` (`UploadFileProperties.java:14-37`); `TaskStore` is chosen as "file when `metadata-dir` is set, otherwise memory" (`UploadFileAutoConfiguration.java:42-49`).

rc.2 plan: T1 expired-task cleanup (TTL/GC), T2 atomic merge, T3 orphan-data GC, T4 async merge, T5 pluggable metadata storage (JDBC/Redis), plus a set of new `upload-file.*` properties.

---

## 1. Question 1: With rc.1 in wide use, does rc.2 introduce severe conflicts?

### 1.1 Data compatibility (mostly compatible; two high-risk cases)

| Item | Analysis | Risk |
| --- | --- | --- |
| Reading old JSON metadata | rc.2 adds `mergeState/mergeError/mergeStartedAt` to `UploadTask`; Gson yields `null` for missing fields, and the plan's "treat as NONE" matches the existing `uploadedChunks` fallback (`FileTaskStore.java:76-89`). **Compatible.** | Low |
| Reverse read (rollback) | rc.2-written JSON has new fields; rc.1 Gson ignores unknown fields by default. But if async merge was enabled and a task is PENDING/RUNNING, rollback leaves it stuck (recoverable by re-issuing merge). **Compatible but needs docs.** | Low |
| Existing in-progress tasks | rc.1 environments hold many "in-progress / long-idle" tasks; their metadata lacks the new fields, and rc.2 keeps the synchronous `merge()` entry. **Compatible.** | Low |
| T1 TTL vs existing tasks | If `cleanup.enabled` defaults to `true` + `task-ttl=24h` per the plan, **in-progress tasks idle for more than 24h are silently deleted on upgrade**, breaking resumable upload. Never happens in rc.1; a default-behavior change. **High.** | **High** |
| T3 orphan GC vs MemoryTaskStore | If `cleanup.orphan-enabled` defaults to `true` and production uses `MemoryTaskStore` (`list()` is empty after restart), the `run-on-startup` scan treats **all** on-disk chunk dirs and merged files as orphans and deletes them. Delete-on-startup, data unrecoverable. **High.** | **High** |

### 1.2 Config compatibility (no key conflicts; defaults are the risk)

- All rc.2 properties are new keys (`merge.fsync/cleanup.*/async-merge.*/metadata-store/jdbc.*/redis.*`); no collision with the 8 rc.1 keys. Spring Boot `@ConfigurationProperties` ignores unknown keys, so **existing config keeps working after upgrade**. Low risk.
- **The risk is in the defaults** (see the two high-risk cases above and the switch matrix in section 2).
- For pure Servlet deployments, new init-params share the property names; old `web.xml` files lack them → defaults apply. Whether the default is safe decides the outcome.

### 1.3 API / interface compatibility (compatible)

- The three existing actions are kept; T4 only **adds** `mergeAsync` / `mergeStatus`, so old front-ends are unaffected. Low risk.
- `ChunkStorage.listIdentifiers()` is a new **default** method (returns an empty set); existing custom implementations need no change. Low risk.
- `merge()` is kept as the synchronous entry (default); async is opt-in. Low risk.

### 1.4 Behavioral compatibility (T4/T5 compatible; T1/T2/T3 need care)

- **T1**: enabling by default changes old behavior (silent task deletion) → default must be off.
- **T2**: atomic merge has no switch; it is an internal implementation change. The external contract is the same (same artifact on success; on failure it changes from "leave a corrupt file" to "delete the temp file"). Client-invisible; a safety improvement. **But concurrency/race verification is required** (see 2.4). Medium risk.
- **T3**: enabling by default plus the two high-risk combinations (MemoryTaskStore, multi-instance, merge race) → default off + guardrails.
- **T4**: off by default; when enabled, "reject new chunks while RUNNING/SUCCEEDED" is an intended, controlled change. Low risk.
- **T5**: `metadata-store=auto` reproduces rc.1 exactly. Low risk. The real pitfalls are **migration** (see 3.1) and **multi-instance** (see 3.3).

### 1.5 Risk summary

| # | Conflict | Level | Trigger |
| --- | --- | --- | --- |
| C1 | T1 on by default silently deletes long-idle tasks | High | upgrade without config change |
| C2 | T3 + MemoryTaskStore + run-on-startup = delete on startup | High | default on + memory store |
| C3 | T3 race with concurrent merge/upload deletes live data | Medium | orphan cleanup enabled |
| C4 | Multi-instance duplicated scheduling / metadata centralized while chunks stay local | Medium | multi-instance |
| C5 | FileTaskStore in-process cache vs multi-process writes | Medium | shared metadata dir, multiple processes |
| C6 | Rollback while async tasks are stuck | Low | async enabled then rollback |
| C7 | Corrupt files left by rc.1 non-atomic merge flagged as orphans by rc.2 | Low | orphan cleanup enabled |

---

## 2. Question 2: Can rc.2 coexist with rc.1 production (default-off / new features inactive)?

### 2.1 Overall conclusion

**In principle yes, but the plan's defaults violate the "new features off by default" goal and must be fixed before "upgrade-is-compatible" holds.**

The compatibility strategy rests on:
1. Every rc.2 feature can be fully disabled by config, and when disabled the behavior matches rc.1 byte-for-byte;
2. API evolution is additive only (new actions, new default methods, synchronous merge kept);
3. Old metadata stays forward-compatible (missing fields fall back).

### 2.2 Switch matrix (plan vs suggested)

| Feature | Property | Plan default | Suggested default | Reason |
| --- | --- | --- | --- | --- |
| T2 atomic merge | `merge.fsync` | `true` | `true` (keep) | performance only; no external behavior change |
| T1 expiry cleanup | `cleanup.enabled` | `true` | **`false`** | default-on silently deletes existing tasks (C1) |
| T1 | `cleanup.task-ttl` | `24h` | irrelevant when disabled; loosen if enabled | large files / long-paused uploads |
| T3 orphan GC | `cleanup.orphan-enabled` | `true` | **`false`** | highest delete risk (C2/C3), needs a persistent TaskStore |
| T3 | `cleanup.run-on-startup` | `true` | `false` (only with orphan-enabled) | startup scan = delete on first boot after upgrade |
| T1/T3 | `cleanup.interval` | `1h` | no impact | follows enabled |
| T4 async merge | `async-merge.enabled` | `false` | `false` (keep) | already safe |
| T5 store | `metadata-store` | `auto` | `auto` (keep) | reproduces rc.1 wiring |

### 2.3 The switchless internal change (T2 atomic merge)

T2 cannot be toggled off. Pick one:
- Accept the safety improvement, declare in the CHANGELOG that "merge failure now removes the temp file instead of leaving a corrupt file", and provide an upgrade note;
- or add a `merge.atomic` fallback switch (default `true`) so conservative existing users can return to the rc.1 direct-write path. **Recommended**: implement the switch — cheap, and removes all upgrade doubt.

### 2.4 Compatibility verification

- Add a `compat` test suite: boot rc.2 with "rc.1-generated JSON metadata samples + rc.1 directory layout (chunks/, files/)" and verify merge/progress/download behave like rc.1 under **default** config;
- Verify all new configs in the "off" state start no scheduler thread and change no existing path;
- Upgrade smoke tests with both memory and file TaskStore.

---

## 3. Question 3: Gaps that must be considered

### 3.1 Data migration (T5, not in the plan)

- Existing rc.1 FileTaskStore JSON (including in-progress tasks) → JDBC/Redis needs a **migration tool or script**, otherwise switching `metadata-store` loses all in-progress tasks (resumable upload breaks).
- Consider adding a `schemaVersion` / metadata-format-version field to `UploadTask` for future evolution and migration detection.

### 3.2 Concurrency and consistency (T1/T2/T3 interplay)

- **Cleanup vs merge race**: merge writes temp files `files/<id>/<fileName>.merge-*.tmp`; orphan scanning that cannot tell "in-use temp file" from "leftover" deletes files being written. Scans must exclude active temp files (by timestamp/liveness) and should hold the same per-identifier lock as merge/upload.
- **Cleanup vs upload race**: a chunk being written as `.upload-*.part` may be treated as an orphan. Same handling as above.
- **FileTaskStore cache**: `get()` returns from `cache` without touching disk (`FileTaskStore.java:67-70`); with multiple processes sharing the metadata dir, cache and disk diverge. rc.2 should either document "FileTaskStore is single-process only" or use an invalidation strategy.

### 3.3 Deployment topology (T1/T3/T5, not in the plan)

- **Multi-instance**: every instance would start a T1/T3 scheduler → duplicated cleanup/GC. Needs leader election or a deployment-level opt-out.
- **T5 + local chunks**: once metadata is centralized (JDBC/Redis) while chunks stay on local disks, horizontal scaling makes "chunks uploaded on A, merged on B" fail. Must pair with shared storage (NFS/OSS/S3) or document "central store implies single-instance/shared disk".
- **Shared metadata dir + FileTaskStore**: in-process caches are invisible across processes (see 3.2).

### 3.4 Observability and operations (not in the plan)

- T1/T3 background jobs should expose last-run time, counts, duration, and errors (JMX/metrics/logs), otherwise production incidents are uninvestigatable.
- T4 async-merge queue length and thread-pool saturation alarms.
- `TTL=0` must be implemented as "never clean" and unit-tested, to avoid `0` being treated as "clean immediately".

### 3.5 Release / rollback (not in the plan)

- Add the `compat` tests and the two high-risk regression cases (C1/C2) to `mvn verify`;
- Provide an rc.1 → rc.2 upgrade and rollback matrix document: config list, default changes, "no data migration" statement, rollback notes (async task state, irreversible orphan deletion);
- **Orphan deletion is unrecoverable**: every enabled scenario must instruct "back up the disk data first" in docs and code.

### 3.6 Miscellaneous

- `ATOMIC_MOVE` throws `AtomicMoveNotSupportedException` on some filesystems; T2 must reuse rc.1's existing fallback (`LocalFileChunkStorage.java:70-75`);
- Prefer `FileChannel.force(true)` on the temp file, and fsync the parent directory where needed; otherwise ATOMIC_MOVE durability is not guaranteed on all platforms;
- Confirm dependencies of the new `upload-file-store-jdbc/redis` modules (H2 test-only, Jedis) for license/security before release; the release profile must cover the new modules.

---

## 4. Conclusions & recommendations

1. **No "severe, irreconcilable" conflicts**, but two high-risk defaults **must** be corrected before a safe upgrade: `cleanup.enabled` and `cleanup.orphan-enabled` (and `run-on-startup`) should default to `false`; otherwise an rc.1 production upgrade risks silently deleting tasks or deleting all data at startup.
2. The "default-off, new features inactive" compatibility route **holds**, given: T1/T3 off by default, T4 off by default, `metadata-store=auto`, synchronous merge kept, additive-only API changes.
3. Add a `merge.atomic` fallback switch for T2 to remove the only switchless upgrade doubt.
4. The plan must add: legacy-data migration tooling (FileTaskStore → JDBC/Redis), lock coordination between cleanup and merge/upload, multi-instance constraints, observability, an upgrade/rollback matrix, and compat regression tests.

### Pre-release checklist

- [ ] Change `cleanup.enabled` / `cleanup.orphan-enabled` / `cleanup.run-on-startup` defaults to `false`;
- [ ] Evaluate and implement the `merge.atomic` fallback switch;
- [ ] T3 scan excludes active temp files and coordinates with the per-identifier lock;
- [ ] T5 ships FileTaskStore → JDBC/Redis migration tooling/docs;
- [ ] Document multi-instance deployment constraints (leader scheduling, shared storage);
- [ ] Add a `compat` test suite (rc.1 data/layout + default-config regression) and C1/C2 cases;
- [ ] Write the rc.1 → rc.2 upgrade and rollback matrix document;
- [ ] Add `schemaVersion` field and a `TTL=0` semantics unit test.
