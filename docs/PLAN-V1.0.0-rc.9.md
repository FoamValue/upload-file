# V1.0.0-rc.9 Task Plan (Security Closure Inside the Freeze)

> 🇨🇳 [中文](PLAN-V1.0.0-rc.9.zh-CN.md)
>
> rc.8 froze the public API and the `upload-file.*` property surface, and the
> [rc.8 usage feedback](../doc/user-feedback/upload-file-rc8-usage-feedback.md) confirmed the frozen
> surface holds for a production consumer. A full-repository security review from a senior
> Maven-dependency-developer perspective then found **7 issues (2 high + 5 medium)** on the frozen
> surface: quota and size limits trusted client-declared values without counting actual bytes (H1);
> the starter defaulted `max-chunk-size=-1`, leaving request bodies unbounded (H2); a missing
> `chunkMd5` skipped MD5 verification entirely when `verify-checksum` was on (M1); the download
> `finalPath` could escape the merged dir because it was not prefix-checked after canonicalisation
> (M2); access logs concatenated attacker-controlled fields without sanitisation (M3); one dirty
> record aborted the whole orphan-cleanup pass (M4); oversized chunks were fully written to disk
> before being size-checked (M5).
>
> ✅ **Status: implemented and released in `1.0.0-rc.9` (2026-09-28).** T54–T61 are all done:
> actual-byte size counting, tightened defaults + fail-fast startup, `require-checksum`,
> canonical-prefix download validation, log sanitisation, per-entry isolated orphan cleanup, and the
> byte-bounded `saveChunk(..., maxBytes)` overload — see the [Changelog](../CHANGELOG.md). After rc.9
> the frozen surface still holds: `1.0.0` only bumps the version, and re-verts the short-lived rc.9
> `saveChunk` return-type change to a `void` 3-arg signature plus a 4-arg `default` overload
> (binary-compat, see §10).
>
> Feedback source: `doc/user-feedback/upload-file-rc8-usage-feedback.md`.

## 1. Goal and Scope

**Theme**: a **security closure inside the API freeze** — fix the 7 issues found by the review
(quota bypass, unbounded defaults, skippable checksum, path traversal, log injection, cleanup
interruption, write-then-check) with **additive changes and tightened defaults only**; no new feature
and no frozen contract is broken. After rc.9 the API and the `upload-file.*` property surface stay
frozen; `1.0.0` is a version bump and announcement only.

| ID | Gap | Description | rc.9 task |
| --- | --- | --- | --- |
| G28 (H1) | Quota / size limits trust declared values | `uploadChunk` counts client-declared `fileSize`; a declared `0`/negative value bypasses `maxChunkBytes` and the quota — disk-exhaustion attack surface | T54 |
| G29 (H2) | Unbounded default request configuration | `max-chunk-size` defaults to `-1`; when a host only sets service-level limits the request body is unbounded — insecure-by-default | T55 |
| G30 (M1) | MD5 verification is skippable | with `verify-checksum=true`, a chunk missing `chunkMd5` skips verification entirely instead of failing | T56 |
| G31 (M2) | Download `finalPath` can escape the merged dir | the prefix check runs on the non-canonical path; a crafted path survives canonicalisation and reads outside the merged dir | T57 |
| G32 (M3) | Access-log injection | access logs concatenate attacker-controlled identifier/action fields without filtering newline / CR / control characters (CWE-117) | T58 |
| G33 (M4) | One dirty record aborts orphan cleanup | a single corrupted identifier throws and stops the whole `cleanupOrphans` pass | T59 |
| G34 (M5) | Oversized chunks written before being checked | the chunk is fully streamed to disk first, then compared against `maxChunkBytes` — write amplification | T60 |

## 2. Architecture and Constraints

rc.9 **adds no Maven artifact and no module**; javax and jakarta lines stay aligned at
`1.0.0-rc.9`, and all shared logic remains in `upload-file-core`.

- **Freeze-compatible**: every change is an **additive** API/SPI addition (new property
  `require-checksum`, new `default` method `saveChunk(..., maxBytes)`) or a **default-value
  tightening** (`max-chunk-size` 10 MB, fail-fast startup) — no member is removed, no existing
  signature changes (the short-lived rc.9 `saveChunk` return-type change is re-verted at GA, §10).
- **Server-side truth**: size/quota accounting must come from **actual bytes observed on disk**
  (or a counting stream), never from client-declared values.
- **Fail-fast over warn**: an endpoint configuration that is unbounded in every dimension must not
  start; explicit configuration always wins and keeps its exact behavior.
- **Rollback-safe**: no disk-layout / task-metadata format change; no Redis index structure change;
  the default `task-store` path is byte-for-byte unchanged from rc.8.
- **API freeze holds**: rc.9 is the security closure *inside* the freeze; `1.0.0` changes no
  behavior except the `saveChunk` binary-compat revert (§10).

## 3. Tasks

### T54 Actual-byte quota / size accounting (core, H1 / G28)

- **Files**: `upload-file-core/.../core/service/ResumableUploadService.java` (uploadChunk path),
  `ResumableUploadServiceTest` (+ `CoreEdgeCoverageTest`).
- **Design**:
  - Reject a negative `fileSize` up front (invalid declared value, `400`-class error);
  - after saving, re-check the chunk's **on-disk length** against `maxChunkBytes`; an oversized chunk
    is **deleted and rejected** — client-declared values are no longer trusted for accounting;
  - the quota counter keeps counting actual bytes (the byte-bounded write in T60 guarantees the
    counter cannot be inflated by a declared value).
- **Acceptance**: declaring `fileSize=0` or negative does not bypass `maxChunkBytes`/quota; an
  over-limit chunk leaves no on-disk residue; normal chunk upload behavior is byte-for-byte rc.8.
- **Estimate**: 1 person-day.

### T55 Bounded defaults + fail-fast startup (both starters + both servlet lines, H2 / G29)

- **Files**: `UploadFileProperties` (both starter lines; `max-chunk-size` default `10 MB`),
  `UploadFileAutoConfiguration` (both starter lines; request-limit derivation + fail-fast),
  `UploadFileContext` (both plain-Servlet lines; same derivation for init-param config), tests.
- **Design**:
  - `max-chunk-size` default `-1` → **10 MB** (breaking-default: deployments that never configured it
    get a bounded chunk; explicit values unchanged);
  - an unset `max-request-size` is derived from `max-chunk-size` / `max-file-size` **+ 1 MB slack**
    (multipart boundaries/headers);
  - if request, chunk and file limits are **all unbounded**, **startup fails**
    (`IllegalStateException`) instead of running insecure-by-default; setting any one limit restores
    normal startup.
- **Acceptance**: with nothing configured, startup derives a bounded container limit (10 MB chunk);
  all three unbounded fails startup with a clear message; explicit `max-request-size` is unchanged;
  mirrored on both lines (starter + plain servlet, javax + jakarta).
- **Estimate**: 0.5 person-day.

### T56 Non-skippable checksum verification (core + both starters, M1 / G30)

- **Files**: `UploadFileProperties` (both starter lines; new `require-checksum`),
  `UploadFileAutoConfiguration` (both starter lines; wire-through),
  `ResumableUploadService` (verification path), tests.
- **Design**: new `upload-file.require-checksum` (default `false`, backward compatible); when
  `verify-checksum + require-checksum` are both enabled, a chunk missing `chunkMd5` is **rejected and
  deleted** — verification can no longer be skipped by omitting the field.
- **Acceptance**: with the pair enabled, a `chunkMd5`-less chunk fails (documented `400` semantics)
  and leaves no residue; `verify-checksum` alone keeps rc.8 behavior; both lines tested.
- **Estimate**: 0.5 person-day.

### T57 Download path canonical-prefix validation (core, M2 / G31)

- **Files**: `ResumableUploadService` → `ResumableDownloadService.resolveFile` (canonicalisation +
  prefix check), `DownloadServletTest` (both servlet lines), tests.
- **Design**: canonicalise **both** the merged dir and the requested path
  (`getCanonicalFile()`); the resolved file must stay **under** the merged dir; anything outside is
  treated as **absent** (404 semantics) — a canonicalisation-escaped traversal cannot read files
  outside the merged dir.
- **Acceptance**: a crafted `finalPath` escaping the merged dir resolves as absent; legitimate merged
  files resolve unchanged; Range/download behavior is byte-for-byte rc.8 for valid paths.
- **Estimate**: 0.5 person-day.

### T58 Access-log injection filtering (both servlet lines + both starters, M3 / G32)

- **Files**: `UploadFileContext` (both plain-Servlet lines; access-log writer),
  `UploadFileAutoConfiguration` access-log listener (both starter lines), tests.
- **Design**: every value written to the access log passes through `sanitizeLog()`, which strips
  newline / carriage-return / tab and other control characters (CWE-117); the log format is
  unchanged, only the values are sanitised.
- **Acceptance**: an identifier/action containing `\n`/`\r`/control characters produces a single
  sanitised log line; normal logs are byte-for-byte rc.8; mirrored on all four wiring paths.
- **Estimate**: 0.5 person-day.

### T59 Per-entry isolated orphan cleanup (core, M4 / G33)

- **Files**: `StorageCleanupService.cleanupOrphans` (per-identifier isolation),
  `StorageCleanupServiceTest`, `StorageCleanupService` `hasTask()` handling.
- **Design**: wrap each identifier's cleanup in its own `try-catch` so one bad entry no longer stops
  the pass; `hasTask()` treats **illegal identifier names** as "no task" (deleteable as orphan); the
  error is logged and the pass continues.
- **Acceptance**: with one corrupted identifier injected, the remaining identifiers are still
  cleaned in the same pass; a single failure never aborts the loop; normal cleanup behavior
  unchanged.
- **Estimate**: 0.5 person-day.

### T60 Byte-bounded streaming `saveChunk(..., maxBytes)` (core, M5 / G34)

- **Files**: `upload-file-core/.../core/storage/ChunkStorage.java` (new `default` overload),
  `LocalFileChunkStorage` (overrides), affected tests/mocks.
- **Design**:
  - add `default long saveChunk(String identifier, int chunkIndex, InputStream in, long maxBytes)`
    that **counts actual bytes** (via `CountingInputStream`) and **aborts mid-write** — cleaning up
    the partial chunk — when the limit is exceeded; it returns the written byte count;
  - the default implementation delegates the write through the counting stream, so the
    actual-byte guarantee holds for **every** `ChunkStorage` implementation without changes;
  - the existing 3-arg `saveChunk(String, int, InputStream)` stays as-is during rc.9 (the GA revert
    of its rc.9 return-type change is handled in §10).
- **Acceptance**: a chunk exceeding `maxBytes` is aborted mid-write and leaves no residue; the
  returned count equals the on-disk bytes; a custom `ChunkStorage` that only implements the 3-arg
  method still gets the bound via the default.
- **Estimate**: 1 person-day.

### T61 Docs / tests / version / release (H1–M5 sync + rc.9 release)

- **Files**: `CHANGELOG(.zh-CN).md`, `README(.zh-CN).md`, `docs/PLAN-V1.0.0(.zh-CN).md` (SOW §3
  rc.9 table + GA binary-compat note), `docs/DESIGN(.zh-CN).md` (rc.9 security mechanisms),
  `docs/API(.zh-CN).md` (400 semantics for `require-checksum`), `docs/ROADMAP(.zh-CN).md` (rc.9
  entry + GA registration), new `security-fix-report/security-fix-report.html` (root cause / fix /
  verification per issue), parent POM + every module POM `1.0.0-rc.8 → 1.0.0-rc.9` (incl.
  `upload-file-bom` and the three demos), local `.m2` sync via `mvn install`.
- **Design**: the security-fix report documents each of the 7 issues with attack path, fix and the
  verification breakdown; README/SOW carry the breaking-default notes (`max-chunk-size` 10 MB,
  fail-fast startup, `require-checksum`); the SOW §3 table adds the rc.9 surface and the §GA note
  records the `saveChunk` binary-compat revert.
- **Acceptance**: **580 unit tests, 0 failures**; full JDK 17+ reactor `mvn verify` green; zh/en
  docs in sync.
- **Estimate**: 0.5 person-day.

## 4. New Configuration and SPI Surface

| Kind | Item | Default | Breaking |
| --- | --- | --- | --- |
| Property | `upload-file.require-checksum` | `false` | No (new property; additive) |
| Property | `upload-file.max-chunk-size` | `10 MB` (was `-1`) | **Yes** (breaking-default: default value only; explicit config unchanged) |
| Behavior | request/chunk/file limits all unbounded | **startup fails** (`IllegalStateException`) | **Yes** (fail-fast; setting any one limit restores startup) |
| Behavior | unset `max-request-size` | derived from `max-chunk-size`/`max-file-size` (+1 MB slack) | No (default-only derivation) |
| API | `ChunkStorage.saveChunk(String, int, InputStream, long maxBytes)` (new `default`, returns written bytes) | counting-stream delegate | No (additive; 3-arg `void` restored at GA, §10) |
| SPI | `StorageCleanupService.cleanupOrphans` | per-entry `try-catch` isolation | No (behavior hardening) |

## 5. Compatibility (rc.8 → rc.9)

| Behavior | rc.8 | rc.9 | Notes |
| --- | --- | --- | --- |
| Quota / size limits | trust client-declared values | count **actual on-disk bytes** | H1 fix; declared value still bounds the pre-check |
| `max-chunk-size` default | `-1` (unbounded) | `10 MB` | **breaking-default**; explicit config unchanged |
| Startup with all limits unset | runs insecure-by-default | **fails fast** | H2 fix; setting any one limit restores |
| MD5 verification | missing `chunkMd5` skips verification | rejected + deleted with `require-checksum` | M1 fix; `require-checksum=false` keeps rc.8 |
| Download `finalPath` | non-canonical prefix check | canonical-prefix check; escape = absent | M2 fix |
| Access log | raw concatenation | `sanitizeLog()` (CWE-117) | M3 fix; log format unchanged |
| Orphan cleanup | one dirty record aborts the pass | per-entry isolation, pass continues | M4 fix |
| Oversized chunk | fully written, then checked | aborted mid-write, partial cleaned up | M5 fix; 3-arg `void` restored at GA (§10) |
| Success bodies / endpoints / disk & metadata formats / frozen API & property keys | — | unchanged | — |

- No new artifact; existing coordinates and `upload-file.*` keys unchanged; core manual wiring
  still works.
- No disk-layout / task-metadata format change; no Redis index structure change.

## 6. Test Plan

- core: negative `fileSize` rejection; post-save size re-check with delete on over-limit;
  canonical-prefix escape cases resolve as absent; `sanitizeLog` control-character stripping;
  cleanup per-entry isolation with an injected dirty identifier; byte-bounded `saveChunk` abort +
  cleanup.
- starter + servlet (javax + jakarta, mirrored): 10 MB default derivation, unset `max-request-size`
  derivation, tri-state fail-fast startup; `require-checksum` accept/reject; access-log sanitisation.
- Compatibility regression: default `task-store` path and success bodies byte-for-byte unchanged
  from rc.8; `verify-checksum` alone keeps rc.8 behavior; explicit `max-request-size` unchanged.
- Release: full JDK 17+ reactor `mvn verify` — **580 unit tests, 0 failures**; JaCoCo per module.

## 7. Documentation and Examples

- New `security-fix-report/security-fix-report.html`: attack path, fix (code) and verification
  (580-test breakdown) for each of the 7 issues.
- `CHANGELOG(.zh-CN).md`: rc.9 entry (7 fixes, 2 high + 5 medium) + breaking-default notes.
- `README(.zh-CN).md`: rc.9 upgrade notes (`max-chunk-size` 10 MB default, fail-fast startup,
  `require-checksum`).
- `docs/API(.zh-CN).md`: `400` when `require-checksum=true` and `chunkMd5` is missing.
- `docs/DESIGN(.zh-CN).md`: rc.9 security mechanisms (actual-byte counting, canonical validation,
  log sanitisation, cleanup isolation, byte-bounded writes); `saveChunk(maxBytes)` extension point.
- `docs/ROADMAP(.zh-CN).md` / `docs/PLAN-V1.0.0(.zh-CN).md`: rc.9 security-closure entry → flip on
  release; GA registration; SOW §3 rc.9 table + GA binary-compat note.
- New `docs/PLAN-V1.0.0-rc.9(.zh-CN).md` (this plan).

## 8. Milestones and Release

1. **M1** (T54, T55): H1/H2 — actual-byte accounting + bounded defaults / fail-fast startup
   (highest priority, GA hard gate);
2. **M2** (T56, T57): M1/M2 — non-skippable checksum + download path validation;
3. **M3** (T58, T59): M3/M4 — log sanitisation + cleanup isolation;
4. **M4** (T60): M5 — byte-bounded streaming write;
5. **M5** (T61): docs / security-fix report / version `1.0.0-rc.8 → 1.0.0-rc.9` / full `mvn verify`
   (580 tests) / publish both lines + BOM;
6. **M6 (GA)**: `1.0.0-rc.9 → 1.0.0`, version bump and announcement only, plus the `saveChunk`
   binary-compat revert (§10).

## 9. Out of Scope (post-GA 1.0.x / 1.1)

- **Ecosystem (ROADMAP P2-5)**: Prometheus metrics, upload-completed webhook, content-addressed
  instant upload, whole-file SHA-256, object-storage backend, tus protocol, virus-scan hook — per
  the ROADMAP.
- **Per-user quota / rate limiting, multi-tenant namespaces, recycle bin / versioning, pre-signed
  downloads** — scheduled after GA as needed.
- **javax line convergence**: maintenance from GA, removed at 2.0 (SOW policy).

## 10. GA Handover (rc.9 → 1.0.0)

The frozen surface stays frozen after rc.9:

- **Additive-only, defaults-tightened**: rc.9 adds exactly one property (`require-checksum`) and one
  SPI method (`saveChunk(..., maxBytes)` `default`), plus the two controlled default changes
  (`max-chunk-size` 10 MB, fail-fast tri-state) recorded in §4/§5. No frozen API, SPI or property
  key is removed or re-semanticised.
- **The one code change in `1.0.0` is a binary-compat revert**: rc.9 drafted
  `ChunkStorage.saveChunk(String, int, InputStream)` with return type `long`; the revapi gate (rc.7/
  rc.8 baseline) flags a return-type change as **non-additive** — custom `ChunkStorage`
  implementations compiled against rc.7/rc.8 would fail at the bytecode level. `1.0.0` restores the
  3-arg method to `void` (frozen since rc.7) and keeps the byte-bounded behavior through the 4-arg
  `default` overload (counting stream; `LocalFileChunkStorage` overrides it), so the M5 security
  guarantee holds for every implementation without breaking the SPI. Affected test mocks are fixed
  with the revert.
- **GA admission**: revapi green against the rc.7/rc.8 baseline, `upload-file-bom` publishable,
  full JDK 17+ reactor `mvn verify` green (**580 tests, 0 failures** at rc.9).
