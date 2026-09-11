# V1.0.0-rc.6 Task Plan (Commercial HTTP-Layer Adoption: Security & Audit Alignment)

> 🇨🇳 [简体中文](PLAN-V1.0.0-rc.6.zh-CN.md)
>
> rc.5 shipped the jakarta starter, but the first real integration evaluation
> (path-finder, `ADR-001-upload-file-starter-jakarta` + `UPGRADE-upload-file-starter-jakarta`, concluding
> **"do not migrate"**) showed the `-jakarta` artifacts are a drop-in for the **javax starter**, not for a
> **"manual core wiring + hand-rolled MVC endpoints"** integration. Root causes: (1) servlets are registered
> unconditionally and win over MVC, (2) the HTTP error/audit contract is taken over and is not customizable,
> (3) access decisions can only be expressed as a hard-wired `401` throw and are not observable, (4) multipart
> and fine-grained HTTP behaviours differ. rc.6 turns the official HTTP layer into something a commercial
> project can adopt directly: controllable endpoints, an error-code / error-body standard, an object-based
> `AccessControl` decision path with audit hooks, a configurable multipart strategy, behaviour convergence,
> and a migration guide. Feedback sources: `doc/user-feedback/upload-file-usage-feedback.md` (§7
> P0-2/P1-4/P1-7) and the path-finder ADR/UPGRADE evaluation outcomes.
>
> ✅ **Status: the core of `1.0.0-rc.6` is implemented and released.** T25–T31 and T34 are done: endpoint
> control, additive decision-returning `AccessControl` with audit hooks, symbolic codes + uniform error body,
> multipart strategy, and servlet behaviour convergence are shipped — see the [Changelog](../CHANGELOG.md).
>
> ⚠️ **Outstanding (follow-up):** the T32 "hand-rolled MVC endpoints → official Servlet" migration guide
> (coordinate/difference matrices, one-line breaking-default configs, `check()`→`decide()` snippet) is not yet
> written; the T33 commercial wiring currently lives only as `application.yml` comments in `boot4-demo`, without
> the planned demo config class (`AccessControl.decide()` + `AccessDecision.deny(403,...)` + an
> `AccessControlListener` audit example).

## 1. Goals & Scope

**Theme:** make the official servlet/starter HTTP layer *directly adoptable by commercial systems* that
already have session auth, a unified response envelope, and their own download endpoints — while shrinking the
default attack surface and adding audit observability.

| # | Gap | Description | rc.6 task |
| --- | --- | --- | --- |
| G1 | Endpoint registration is not controllable | both `ServletRegistrationBean`s are unconditional (no switch, no override point); no "beans-only" mode; `/download` is registered by default | T29 |
| G2 | Denials are only expressible as a `401` throw | `AccessControl` offers no decision object; `401` vs `403` (unauthenticated vs forbidden) cannot be expressed; decision points are not observable | T25, T26 |
| G3 | No audit observability | neither allow nor deny decisions leave a structured trace unless the host hand-wires it | T26 |
| G4 | HTTP failures are not customizable and partly mis-coded | fixed Gson envelopes (endpoint-empty-objects used as error bodies), no symbolic error codes; `GET /upload` with an unknown or missing action is treated as *progress*; non-`UploadErrorCode` server faults in merge/cancel/status are collapsed to `400` | T27, T28 |
| G5 | Starter multipart limits override the Spring-global semantics | `max-chunk-size` / `max-request-size` are injected into `@MultipartConfig`, bypassing `spring.servlet.multipart.*` | T30 |
| G6 | No migration guide / template for third parties | README/API/demos only cover green-field usage of the official endpoints; the "self-built MVC endpoint → official Servlet" path is undocumented | T32, T33 |

## 2. Architecture & Constraints

No new Maven artifacts in rc.6; the javax and jakarta servlet/starter lines evolve in lockstep at the same
`1.0.0-rc.6` version (source twins stay mirrored; shared logic lives in `upload-file-core`).

- **Additive-first.** Every new capability is additive (new property, new SPI, or a new method/default method);
  nothing that existing consumers implement is removed. Breaking default changes are limited to two defect-fix
  behaviours (T28) and the `/download` minimal-exposure default (T29).
- **Off-by-default (zero-regression)**: new properties/SPIs reproduce rc.5 behaviour by default — error-body
  standard, access-log, endpoint switches, multipart strategy, the optional cancel-not-found mapping.
- **`AccessControl` evolves, not breaks**: the existing `void check(...) throws AccessDeniedException` stays a
  valid implementation point (`@Deprecated`, bridged by a default method); a new decision-returning method is
  additive. Boot 2 / javax manual-wiring consumers compile and behave unchanged in rc.6 unless they opt into
  the new API. See T25.
- **Commercial security posture**: smallest default exposure (`/download` off), deny status decided by the
  host's decision (`401`/`403` distinguishable), decisions observable by both the MVC and Servlet paths through
  one hook, and a stable symbolic error-code catalog that both code and docs read from one source.
- No `upload-file.*` property is removed; existing property defaults are unchanged; no data layout / task
  metadata / disk format change (upgrade is restart-only).
- Core manual wiring remains fully supported and is updated together with the starter.

## 3. Task Breakdown

### T25 Decision-returning `AccessControl` (additive bridge) (core, G2)

- **Files**: `AccessControl`, `PermitAllAccessControl`, `TokenAccessControl`, `AccessDeniedException` (optional
  `status`), new `AccessDecision`; call sites in `ResumableUploadService` / `ResumableDownloadService`.
- **Approach** (additive, keeps every existing implementation compiling):
  - keep `void check(identifier, action, token) throws AccessDeniedException` untouched but `@Deprecated`;
  - add `default AccessDecision decide(identifier, action, token)` whose base implementation calls `check()` and
    maps "returned normally" → `allow()`, "threw `AccessDeniedException`" → `deny(e.getStatusCode() or 401,
    e.getMessage())`. New implementations override `decide()` (and may stop overriding `check()`);
  - `AccessDecision.allow()` / `AccessDecision.deny(status, reason)`; `AccessDeniedException` gains an optional
    status defaulting to `401`;
  - the core services call `decide()`; on a denial they throw `AccessDeniedException` carrying the decision
    status, so HTTP semantics stay unchanged for callers that did not opt in, and a `deny(403)` surfaces as
    `403`.
- **Acceptance**: unit tests for both built-ins, the `decide()`↔`check()` bridge both directions, and status
  propagation; a legacy implementation that only overrides `check()` still compiles and enforces identically;
  `deny(403)` reaches the HTTP layer as `403`; default `401` unchanged.
- **Estimate**: 1 person-day.

### T26 Decision hooks + access-log (core, G2/G3)

- **Files**: new optional SPI `AccessControlListener`; `ResumableUploadService` / `ResumableDownloadService`
  get `addAccessControlListener(...)`; starter auto-config aggregates `ObjectProvider<AccessControlListener>`;
  `observability.access-log` (default `false`).
- **Approach**: every entry-point access check goes through a shared gate that (a) evaluates `decide()`,
  (b) notifies all listeners `onDecision(identifier, action, decision, elapsedNanos)` — `elapsed` is the
  duration of that single decision, (c) when `observability.access-log=true` writes one structured line
  (action/identifier/decision/status/elapsedMs, `CLEANUP_STATS_LOG` style). The gate lives in core, so the MVC
  and Servlet paths and the download path emit consistent events before any denial is thrown; a host
  (path-finder) implements `AccessControlListener` to write its own FORBIDDEN audit rows instead of hand-wiring
  a `LogService` at each deny point.
- **Acceptance**: listeners fire on allow and deny for upload/progress/merge/async-merge/download/cancel in both
  MVC (service) and Servlet flows; access-log line toggles on/off; no listener and log off = no behaviour change.
- **Estimate**: 1 person-day.

### T27 Stable symbolic error codes + unified error body (core, G4)

- **Files**: `UploadErrorCode` gains `code()`; each typed exception returns a stable constant; new
  `UploadErrorCodes` catalog class (single source of truth); new model `UploadHttpError`
  (`code/status/message/identifier/action`) and an `UploadErrorRenderer` SPI with `legacy` and `standard`
  renderers.
- **Approach**:
  - **codes are always available (not gated)** — `UploadErrorCode.code()` is a default method backed by the
    explicit `UploadErrorCodes` catalog (not derived from class names, so codes never drift on rename); the
    catalog is the single source that both the renderer and API.md (T32) read;
  - the **error-body shape is orthogonal to codes**: a new opt-in property selects the body shape —
    `legacy` (rc.5 per-endpoint empty-object JSON, default) or `standard` (`UploadHttpError` + the catalog
    code). A host that needs its own envelope (e.g. path-finder `ApiResponse`) supplies an `UploadErrorRenderer`
    bean instead of using `standard`.
- **Acceptance**: every typed exception returns a non-null catalog code; code stability locked by tests (renames
  must update the catalog, not drift); `legacy` output byte-identical to rc.5; renderer unit tests.
- **Estimate**: 0.5 person-days.

### T28 Servlet behaviour convergence + error rendering (servlet twins, G4)

- **Files**: `UploadServlet` / `DownloadServlet` / `UploadFileContext` in `upload-file-servlet` and
  `upload-file-servlet-jakarta`; their test suites.
- **Approach** (defect fixes apply to the **default**, flagged breaking):
  - `GET /upload` requires a known action: a **missing** `action` or an unknown value returns `400`
    (`MISSING_ACTION` / `UPLOAD_UNKNOWN_ACTION`). Note: the informal "bare `GET /upload?identifier=X` to poll
    progress" pattern (today silently treated as progress) now returns `400` — listed in the acceptance matrix;
  - non-`UploadErrorCode` server faults on merge/cancel/status/progress return `500` with a generic message
    (was: collapsed to `400`);
  - new init-params / properties: error-body `legacy|standard` (default `legacy`) and an optional
    `cancel-not-found-status` (`404` default, `200` for idempotent reclaim). Success bodies are unchanged in
    both modes.
- **Acceptance**: javax and jakarta test suites mirror each other; the acceptance matrix explicitly covers
  `GET` missing-action vs unknown-action → `400`; `legacy` mode keeps rc.5 byte-for-byte output for every
  existing test case.
- **Estimate**: 1.5 person-days.

### T29 Endpoint registration control + beans-only mode (starter twins, G1)

- **Files**: `UploadFileAutoConfiguration` / `UploadFileProperties` / metadata in both starters.
- **Approach** (single coherent group under `upload-file.endpoint.*`):
  ```yaml
  upload-file:
    endpoint:
      enabled: true           # master; false = beans-only (service beans wired, no servlet registered)
      upload-enabled: true    # register /upload
      download-enabled: false # /download off by default (breaking, minimal exposure)
  ```
  Servlet registration beans gain `@ConditionalOnMissingBean` so a host can supply its own registration. These
  toggles are **starter-level only**; a plain-Servlet host already gates endpoints via web.xml/annotation
  mapping and does not read them.
- **Acceptance**: default context registers `/upload` and no `/download`; `download-enabled=true` restores it;
  `endpoint.enabled=false` leaves all service beans present with no servlet; a custom
  `ServletRegistrationBean` overrides the auto one; no property overlap with a second master switch.
- **Estimate**: 1 person-day.

### T30 Multipart strategy (starter twins, G5)

- **Files**: `UploadFileAutoConfiguration` / `UploadFileProperties` in both starters; starter tests.
- **Approach**: new `upload-file.multipart.strategy` (`component` | `spring` | `unlimited`, default
  `component` = rc.5 behaviour):
  - `spring` — build the servlet `MultipartConfigElement` from `spring.servlet.multipart.*`. **Warning (doc):
    Boot's defaults are `max-file-size=1MB / max-request-size=10MB`; unless the application raises them, a
    5 MB chunk will be rejected at the container before business logic runs.** The guide documents the required
    `spring.servlet.multipart.max-file-size/max-request-size` alignment;
  - `unlimited` — container limits off (`-1`), only the service-layer `max-chunk-size` / `max-file-size` checks
    apply, and `upload-file.max-request-size` is ignored. **Warning (doc): this disables the container-level DoS
    guard — an oversized request body is spooled to a temp file before the service rejects it.**
- **Acceptance**: three strategies tested on both starters; default (`component`) unchanged; both caveats are in
  the configuration table.
- **Estimate**: 0.5 person-days.

### T31 Starter property wiring + javax/jakarta parity tests (G1/G3/G4/G5 wiring)

- **Files**: both starters' auto-config + test suites (defaults, store selection, cleanup, async merge, access
  control, servlet registration incl. `action=cancel`, endpoints, error body, access-log).
- **Approach**: wire every rc.6 property from `UploadFileProperties` into the auto-config and the servlet
  init-params; assert drop-in equality between the javax and jakarta starters for the whole rc.6 property set
  and bean graph, including the new toggles and the parity of T28/T29/T30 behaviour.
- **Acceptance**: both starter suites green; property set / bean graph equivalent across the two lines; custom
  beans still overridable via `@ConditionalOnMissingBean`.
- **Estimate**: 1.5 person-days.

### T32 Migration guide + error-code catalog (G6 docs)

- **Files**: `README(.zh-CN).md`, `docs/API(.zh-CN).md`, `docs/DESIGN(.zh-CN).md`.
- **Approach**: new "hand-rolled MVC endpoint / manual core wiring → official Servlet (rc.6)" guide: the
  javax↔jakarta coordinate matrix, the diff matrix (success objects, failure bodies, deny `401`/`403`, missing/
  unknown action, cancel semantics, `/download` surface, multipart), config one-liners for each breaking
  default, the `AccessControl` additive-bridge migration snippet (legacy `check()` vs `decide()`), the symbolic
  error-code catalog rendered from the same `UploadErrorCodes` source, and the minimal-exposure recommendation
  (`endpoint.download-enabled` policy, audit via `AccessControlListener`).
- **Acceptance**: a path-finder-like reader knows exactly which properties to flip and which files to touch;
  API.md code catalog matches `UploadErrorCodes` (single source); the `/download`-default-off upgrade note is
  prominent (README top + CHANGELOG).
- **Estimate**: 1 person-day.

### T33 Enterprise-style demo enhancement (G6 example)

- **Files**: `example/upload-file-boot4-demo` (frontend + `application.yml` + a demo config class).
- **Approach**: extend the Boot 4 demo with the commercial wiring as a documented profile/example:
  session-owner `AccessControl` (overrides `decide()` returning `AccessDecision.deny(403, ...)`), an
  `AccessControlListener` that logs an audit line, `endpoint.download-enabled=false`, and one variant that uses
  `standard` error bodies. Boot 2 / javax demo gets a short note only.
- **Acceptance**: demo runs on Boot 4 with the enterprise wiring; walkthrough documented in README.
- **Estimate**: 1 person-day.

### T34 Changelog / roadmap / release (G1–G6 sync + release)

- **Files**: `CHANGELOG(.zh-CN).md`, `docs/ROADMAP(.zh-CN).md`, `docs/DESIGN(.zh-CN).md`,
  `docs/PLAN-*` status flips, version `1.0.0-rc.5 → 1.0.0-rc.6` in the parent and module POMs,
  `.flattened-pom.xml`, demos.
- **Approach**: document the rc.6 entry (breaking defaults list + additive list) with a prominent
  `/download`-default-off upgrade note and per-item one-liners, mark the rc.5-era jakarta adoption blocker
  resolved with the rc.6 guide, flip PLAN status to implemented after all tests are green, full `mvn verify` on
  JDK 17+ (javax line stays JDK-8-buildable via `-pl`), release the two lines with the existing release
  profile. The Boot 2 / javax line ships the same `1.0.0-rc.6` with no forced code change (AccessControl stays
  additive), so javax consumers get a pure guidance note, not a breaking migration.
- **Acceptance**: CHANGELOG lists every breaking default and its one-liner; all modules green; release notes
  headline "official HTTP layer is directly adoptable by commercial projects".
- **Estimate**: 1 person-day.

## 4. New Configuration & SPI Surface

| Kind | Item | Default | Breaking? |
| --- | --- | --- | --- |
| Property | `upload-file.endpoint.enabled` (master; `false` = beans-only) | `true` | no (new mode when `false`) |
| Property | `upload-file.endpoint.upload-enabled` | `true` | no |
| Property | `upload-file.endpoint.download-enabled` | `false` | **yes** (was registered by default) |
| Property | `upload-file.http.error-body` (`legacy`/`standard`) | `legacy` | no (opt-in `standard`) |
| Property | `upload-file.http.cancel-not-found-status` | `404` | no (opt-in `200`) |
| Property | `upload-file.observability.access-log` | `false` | no |
| Property | `upload-file.multipart.strategy` | `component` | no |
| SPI/API | `AccessControl.decide(...)` new default method; `check(...)` kept `@Deprecated` | `check()` bridge | **no** (additive) |
| SPI | `AccessControlListener` (additive) | none | no |
| SPI | `UploadErrorRenderer` + `UploadHttpError` (additive) | `legacy` | no |
| API | `UploadErrorCode.code()` symbolic code + `UploadErrorCodes` catalog | always on, from catalog | no |

> Behaviour convergence in T28 (missing/unknown `GET` action → `400`; non-`UploadErrorCode` server faults →
> `500`) and the `/download` default-off (T29) change the **default** and are flagged breaking in CHANGELOG,
> with a regression matrix asserting the new contract.

## 5. Compatibility (rc.5 → rc.6)

| Behaviour | rc.5 | rc.6 | Note |
| --- | --- | --- | --- |
| `AccessControl` | `void check(...)` throws | `check()` kept `@Deprecated` + new `decide()` returns `AccessDecision` | additive; legacy impls unchanged |
| `/download` registration (starter) | always registered | off unless `endpoint.download-enabled=true` | breaking; minimal exposure; prominent upgrade note |
| `GET /upload` missing/unknown action | treated as progress | `400` (`MISSING_ACTION` / `UPLOAD_UNKNOWN_ACTION`) | breaking; defect fix |
| merge/cancel/status server fault | collapsed to `400` | `500` | breaking; defect fix |
| failure body | endpoint empty-object JSON | unchanged (`legacy`); `standard` opt-in | off-by-default |
| access denial status | always `401` | host decision (`401`/`403`) via `decide()` | additive |
| audit | none built-in | `AccessControlListener` + `access-log` | off-by-default |
| error codes | none | symbolic codes always available | additive |
| multipart limits | component props → `@MultipartConfig` | `component` (default) / `spring` / `unlimited` | off-by-default |
| success bodies / endpoints / existing `upload-file.*` keys | — | unchanged | — |

- New artifacts: none. Core manual wiring keeps working and is unaffected unless it opts into `decide()`.
- No data layout / task metadata / disk format changes; upgrading is restart-only (no data migration).
- javax and jakarta twins stay mutually exclusive on one classpath (unchanged from rc.5).

## 6. Test Plan

- Core: `AccessDecision`, the `decide()`↔`check()` bridge (both directions), built-ins (T25), listener +
  access-log (T26), renderer + catalog stability (T27) unit suites; compat regression asserts rc.5 default
  behaviour byte-for-byte where defaults are preserved.
- Servlet (javax + jakarta, mirrored): behaviour-convergence matrix (incl. `GET` missing-action vs
  unknown-action → `400`), error-body `legacy`/`standard`, `cancel-not-found-status`, `500` on server faults.
- Starter (javax + jakarta, mirrored): endpoint master + per-endpoint toggles + beans-only, multipart
  strategies (incl. the `spring` 1 MB default caveat), property-wiring parity, custom-registration override.
- Demo: Boot 4 enterprise-wiring manual E2E; Boot 2 demo regression.
- Full reactor `mvn verify` on JDK 17+; JaCoCo per module.

## 7. Docs & Example Updates

- `README(.zh-CN).md` / `docs/API(.zh-CN).md`: migration guide (T32), error-code catalog rendered from
  `UploadErrorCodes`, new property table, breaking-default notes (incl. the `/download` upgrade note on top);
  `docs/DESIGN(.zh-CN).md`: decision-returning `AccessControl`, listener, renderer, endpoint model.
- `example/upload-file-boot4-demo`: enterprise-style wiring example (T33).
- `docs/ROADMAP(.zh-CN).md` / `CHANGELOG(.zh-CN).md`: rc.6 planned entry → implemented after release.

## 8. Milestones & Release

1. **M1** (T25, T27): core `AccessDecision` bridge + error codes/body — foundation, existing tests green;
2. **M2** (T26): decision hooks + `access-log`;
3. **M3** (T28): servlet behaviour convergence + error rendering on both lines;
4. **M4** (T29, T30, T31): starter endpoint control, multipart strategy, property wiring + parity suites;
5. **M5** (T32, T33): migration guide + enterprise demo;
6. **M6** (T34): version `1.0.0-rc.5 → 1.0.0-rc.6`, full `mvn verify` on JDK 17+, CHANGELOG/ROADMAP sync, and
   release; announcement headline: "official HTTP layer is directly adoptable by commercial projects
   (security & audit aligned)".
