# V1.0.0-rc.4 Task Plan (Feedback-Driven Integration Optimization)

> 🇨🇳 [简体中文](PLAN-V1.0.0-rc.4.zh-CN.md)
>
> rc.3 closed the production-readiness gaps but had not yet been consumed outside this repository. rc.4 is the
> **first release driven by real integration feedback** (PathFinder file-management system,
> `doc/user-feedback/upload-file-usage-feedback.md`, reference commit `62ae062`): it fixes the P0/P1 integration pain
> points that unit tests cannot predict — a stable per-identifier read/cancel contract for the confirm phase, stable
> `UploadErrorCode` HTTP semantics, and documentation that makes cleanup and manual (core) wiring responsibilities
> explicit.
>
> ✅ **Status: all tasks (T14–T18) are implemented and covered by tests in `1.0.0-rc.4`.**
> The remaining top integration blocker — the jakarta (Spring Boot 3/4) adapter, feedback **P0-1** — is deferred past
> rc.4 and tracked in the [ROADMAP](ROADMAP.md).

## 1. Goals & Scope

rc.3 was validated only by this repository's own demo and tests. Its first external consumer (path-finder) integrates
the `upload-file-core` + `upload-file-store-redis` artifacts with **manual (core) wiring** on JDK 26 / Spring Boot 4
(the official starter/servlet are `javax.servlet`-based), which made the following gaps concrete. rc.4 closes the
subset with the highest leverage; the rest stays on the roadmap.

| # | Feedback gap | Description | rc.4 task |
| --- | --- | --- | --- |
| P0-1 (deferred) | jakarta compatibility | official starter/servlet depend on `javax.servlet` and cannot run on Spring Boot 3/4; the consumer falls back to core manual wiring | post-1.0.0 |
| P0-2 | No stable read/cancel contract | the confirm phase re-derives the merged-artifact path from directory conventions instead of reading the returned `finalPath`; abandoned uploads and merged-but-unconfirmed artifacts wait for TTL + scheduler | T14, T15 |
| P0-3 | Cleanup & orphan semantics unclear | `upload-file.cleanup.*` / `async-merge.*` look configured but are dead config under manual wiring; orphan-reclamation semantics are undocumented | T18 |
| P1-4 | HTTP error semantics swallowed | raw generic exceptions fall into a `500` catch-all in the integration layer; client-recoverable failures are reported as server faults | T16, T17 |
| P1-7 | AccessControl has no external-auth story | `PermitAllAccessControl` is always used and no token is ever sent; the SPI looks unusable to teams that already have a login | T18 |
| P1-8 | Range/download helpers re-implemented | `DownloadRange.parse` etc. are not documented as reusable standalone utilities | T18 |

## 2. Architecture & Constraints

Same modular layering, same rule "all business logic lives in core; servlet / starter only wire and map":

```
upload-file (parent POM / aggregator)
├── upload-file-core                        # T14~T17 core logic & exceptions
├── upload-file-servlet                     # T17 error mapping parity + cancel action
├── upload-file-spring-boot-starter         # unchanged in rc.4
├── upload-file-store-jdbc   [optional]     # unchanged
├── upload-file-store-redis  [optional]     # unchanged
└── example/…                               # docs/demo notes only
```

Constraints:
- rc.4 is **purely additive**: no new configuration properties, no behavioral change under the default config, no SPI
  breakage — the public error contract gets *stronger* (broad catches keep working);
- `getTask` / `cancelUpload` are new methods on the concrete service (`ResumableUploadService`), not on an SPI, so
  custom wiring is unaffected;
- the integration layer maps failures through one `UploadErrorCode` interface; the exact code is owned by the
  exception, never guessed from the type or the message.

## 3. Task Breakdown

### T14 Stable per-identifier read for the confirm phase (P0-2)

- **Files**: `ResumableUploadService` (core), README / docs.
- **Approach**: add `Optional<UploadTask> getTask(String identifier)` returning the current store record (a live
  snapshot, treated as read-only). Document that after a successful merge the integration's confirm phase MUST locate
  the merged artifact via `UploadTask.getFinalPath()` (also carried by `UploadResult` / `MergeStatus`) — never by
  guessing the `{storage-dir}/files/<id>/<fileName>` layout or the file-naming strategy.
- **Acceptance**: `getTask` returns the record or empty; `finalPath` is authoritative for confirm; no dependency on
  internal directory conventions; docs include a confirm-phase example.
- **Estimate**: 0.5 person-day.

### T15 Explicit cancellation and data reclaim (P0-2)

- **Files**: `ResumableUploadService` (core), `AccessControl`, `UploadServlet`.
- **Approach**: add `boolean cancelUpload(identifier [, token])`. Under the per-identifier lock it reclaims the data in
  a crash-safe order — chunks first, then the merged-artifact directory, then the task record; returns `false` when no
  task exists and throws `UploadMergeConflictException` (`409`) while an async merge is PENDING/RUNNING. Guarded by the
  new `AccessControl.ACTION_CANCEL`. Servlet exposure: `POST /upload?action=cancel&identifier=...`.
- **Acceptance**: cancel returns 200 (removed) / 404 (nothing existed) / 409 (async merge in flight) / 401 (denied),
  with a JSON body that never leaks internal state; a cancelled identifier can be reused for a brand-new upload.
- **Estimate**: 1 person-day.

### T16 Stable `UploadErrorCode` HTTP semantics (P1-4)

- **Files**: `cn.chenxinjie.uploadfile.core.exception` and `ResumableUploadService` (core).
- **Approach**: introduce `UploadErrorCode.getHttpStatusCode()`. The existing exceptions implement it —
  `ChecksumMismatchException` → `400`, `AccessDeniedException` → `401`, `QuotaExceededException` → `507`. Add three
  typed exceptions, each a subclass of the generic type it replaces so broad catches keep working:
  `UploadValidationException` (`400`, `IllegalArgumentException`), `UploadTaskNotFoundException` (`404`,
  `NoSuchElementException`), `UploadMergeConflictException` (`409`, `IllegalStateException`). Replace the raw generic
  throws in chunk validation / metadata-consistency checks / `merge` / `submitMerge` with the typed ones.
- **Acceptance**: the same failure cases now carry a stable, documented status; code catching the generic types is
  unaffected; an integration needs exactly one `UploadErrorCode` check to map statuses; `400` vs `404` vs `409` are
  distinguishable by clients.
- **Estimate**: 1 person-day.

### T17 Servlet mapping parity and `cancel` action (P1-4)

- **Files**: `UploadServlet`.
- **Approach**: map every failure by its `UploadErrorCode` (`400/401/404/409/507`) with a JSON body instead of a
  blanket `400`; register the `action=cancel` endpoint (T15); a `409` cancel response uses a generic message and never
  exposes internal state.
- **Acceptance**: servlet status codes match the documented `UploadErrorCode` table; cancel behaves as in T15; existing
  servlet tests stay green.
- **Estimate**: 0.5 person-day.

### T18 Documentation: cleanup, manual-wiring, auth bridging and helper reuse (P0-3 / P1-7 / P1-8)

- **Files**: `README(.zh-CN).md`, `docs/API(.zh-CN).md`, `docs/ROADMAP(.zh-CN).md`, `CHANGELOG(.zh-CN).md`,
  `doc/user-feedback/upload-file-usage-feedback.md`.
- **Approach**:
  - **cleanup / orphan semantics**: what `StorageCleanupService` reclaims (incomplete-task chunks, merged-but-
    unconfirmed artifacts, temp files) and when; a consumer wiring core manually must start the cleanup service itself;
  - **manual-wiring responsibility list**: `upload-file.*` properties (including `cleanup.*` and `async-merge.*`) are
    consumed only by the official starter; under manual core wiring the caller owns them — no silent "configured but
    dead" switches;
  - **`UploadErrorCode` status table** and the confirm-phase `finalPath` contract with `getTask` / `cancelUpload`
    usage examples;
  - **AccessControl section**: (a) direct `TokenAccessControl` (token via `X-Access-Token` header / `token` param);
    (b) delegating a custom `AccessControl` to an existing Bearer/SSO login;
  - **`DownloadRange`** documented as a reusable standalone Range parser;
  - record the driving consumer feedback verbatim under `doc/user-feedback/`.
- **Acceptance**: every code-free item in feedback §7 (P0-2, P0-3, P1-4, P1-7, P1-8) is answered in the docs; a new
  integrator reading the README knows exactly what the starter wires vs. what hand wiring must provide.
- **Estimate**: 1 person-day.

### (Deferred) jakarta adapter (feedback P0-1)

- **Not in rc.4.** Add Spring Boot 3/4 (`jakarta.servlet`) variants of `upload-file-servlet` and
  `upload-file-spring-boot-starter`, keeping the `javax.servlet` variants for compatibility. Core already runs on
  Jakarta stacks via manual wiring, so this is an artifact/packaging effort. Tracked as planned after `1.0.0` (see
  the changelog "Unreleased").

## 4. API & Error-Contract Additions

New public API (no new configuration properties in rc.4):

- `ResumableUploadService.getTask(identifier)` → `Optional<UploadTask>` — the task whose `finalPath` is the
  authoritative merged-artifact location for the confirm phase;
- `ResumableUploadService.cancelUpload(identifier [, token])` → `boolean` — explicit cancel + data reclaim;
- `AccessControl.ACTION_CANCEL` guard action;
- `POST /upload?action=cancel&identifier=...` (HTTP `200/404/409/401`, JSON body);
- `UploadErrorCode` status table:

| HTTP | Exception | Meaning |
| --- | --- | --- |
| `400` | `UploadValidationException` / `ChecksumMismatchException` | client-recoverable: bad params, metadata mismatch, size over-limit, checksum failure |
| `401` | `AccessDeniedException` | missing or wrong token |
| `404` | `UploadTaskNotFoundException` | no such upload task |
| `409` | `UploadMergeConflictException` | merge-state conflict: uploading after merge, missing chunks, cancel while async merge is in flight |
| `507` | `QuotaExceededException` | capacity quota exceeded |

## 5. Compatibility

- Purely additive: no new properties, no default-config behavior change; upgrading rc.3 → rc.4 keeps existing behavior;
- New exceptions subclass the exact generic types thrown before (`IllegalArgumentException` / `NoSuchElementException` /
  `IllegalStateException`), so existing broad catches keep working; an integration needs only one `UploadErrorCode`
  check to map statuses;
- `getTask` / `cancelUpload` are added to the concrete service, not to an SPI — custom wiring and stores are
  unaffected;
- The servlet failure mapping narrows from "everything non-quota/access = `400`" to the exact documented status codes,
  a client-visible improvement rather than a contract break.

## 6. Test Plan

- Unit tests keep JUnit 4 + JaCoCo; rc.4 ships core 205 cases and servlet 51 cases, all green;
- New coverage: `cancelUpload` `200/404/409/401` and idempotency (nothing existed → `false`); `getTask` present /
  absent; typed-exception status codes; servlet `cancel` action + `UploadErrorCode` mapping; regression on the
  existing merge / async-merge state machine.

## 7. Docs & Example Updates

- `README(.zh-CN).md` + `docs/API(.zh-CN).md`: confirm-phase `finalPath` contract, `getTask` / `cancelUpload` and
  `action=cancel`, `UploadErrorCode` status table, cleanup & orphan-reclamation semantics, manual (core) wiring
  responsibility list, AccessControl Bearer/SSO note, `DownloadRange` reuse;
- `docs/ROADMAP(.zh-CN).md`: mark rc.4 done and the jakarta adapter as the planned follow-up;
- `CHANGELOG(.zh-CN).md`: record the feedback-driven rc.4 changes and note the jakarta deferral;
- `doc/user-feedback/upload-file-usage-feedback.md`: add the consumer's verbatim feedback;
- Example/demo code is unaffected (the demo already uses the official starter).

## 8. Milestones & Release

1. **M1**: T16 + T17 — stable error semantics end-to-end (core exceptions + servlet parity);
2. **M2**: T14 + T15 — stable read/cancel contract for the confirm phase;
3. **M3**: T18 — documentation and the verbatim feedback record;
4. **M4**: version `1.0.0-rc.4`, publish to Maven Central via the existing release profile; the announcement notes
   that the jakarta adapter (feedback P0-1) is the planned next item.
