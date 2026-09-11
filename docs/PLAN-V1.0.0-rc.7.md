# V1.0.0-rc.7 Task Plan (Store Correctness & Extension-Point Consistency)

> 🇨🇳 [简体中文](PLAN-V1.0.0-rc.7.zh-CN.md)
>
> rc.6 let path-finder replace 226 lines of controlled mirror code with the official HTTP layer (see the
> [rc.6 migration feedback](../doc/user-feedback/upload-file-rc6-migration-feedback.md)), but the same
> feedback exposed **real production risks for `metadata-store=redis`** (unbounded index leak, `list()`
> N+1), a **doc/implementation mismatch** (the starter does not consume a host `UploadErrorRenderer`
> bean), a **safe-default footgun** (unbounded multipart), and missing **multi-instance serialization /
> atomic quota** plus **no compile-time guard for the trusted read API**. rc.7 moves rc.6's "adoptable"
> to "production-trustworthy": fix store correctness, close extension-point gaps, and add guardrails for
> multi-instance and safe defaults.
>
> ✅ **Status: implemented and released in `1.0.0-rc.7`.** T35–T43 are all done: Redis index governance +
> batch reads, the starter consuming a host `UploadErrorRenderer` bean, the multipart safe default, the
> distributed `IdentifierLockProvider`, the atomic `QuotaStore`, the trusted-read API consolidation,
> `AbstractAccessControl`, and the starter wiring/security guardrail — see the [Changelog](../CHANGELOG.md).
>
> Feedback source: `doc/user-feedback/upload-file-rc6-migration-feedback.md` (§5 P0-1/P0-2/P0-3/P0-4,
> P1-1/P1-2/P1-3/P1-4, P2-3, §6 leftovers); historical feedback
> `doc/user-feedback/upload-file-usage-feedback.md`.

## 1. Goals & Scope

**Theme**: pursue a single goal — **correctness / resource safety / extension-point consistency** — with
no large new features. Every P0 and P1 gap raised in the rc.6 feedback is closed; the P2 observability and
ecosystem items are deferred to rc.8.

| ID | Gap | Description | rc.7 task |
| --- | --- | --- | --- |
| G7 | `RedisTaskStore` index set leaks unboundedly | after `sadd` the index has no TTL and members are never cleaned; once a task key TTL-expires, `list()` sees `null` and only `continue`s, never `SREM` | T35 |
| G8 | `RedisTaskStore.list()` is N+1 | `smembers` then `GET` per identifier; every cleanup pass amplifies RTT | T35 |
| G9 | Starter does not consume a host `UploadErrorRenderer` bean | docs promise "provide a bean and it takes effect", but the implementation calls `UploadErrorRenderers.from(property)` directly — the SPI is inert on the starter path | T36 |
| G10 | `multipart.strategy=component` defaults to unbounded | `max-chunk-size`/`max-request-size` default to `-1`; when a host only sets the service-level `max-file-size`, the container is unbounded — a DoS surface | T37 |
| G11 | `IdentifierLock` is in-process only | under horizontal scaling the same identifier's upload/merge are not mutually exclusive; only cleanup has `RedisCleanupLock` | T38 |
| G12 | Global quota `setMaxTotalBytes` is non-atomic | check-then-act race: concurrent uploads can exceed `quota.max-bytes` | T39 |
| G13 | Trusted read API has no compile-time guard | `getProgress(id)` / `isChunkUploaded(id,i)` / `getTask(id)` are public and un-gated, warned only in Javadoc | T40 |
| G14 | `AccessControl` contract is easy to misuse | `decide()` bridges to `check()`, and `check()` defaults to throwing `UnsupportedOperationException`: overriding neither compiles but blows up at runtime | T41 |
| G15 | Starter wiring is redundant + no security warning | `ResumableDownloadService` is built unconditionally even when `/download` is off; with `security.enabled=false` and no host `AccessControl` the endpoints are open with no warning | T42 |

## 2. Architecture & Constraints

rc.7 **adds no Maven artifact**; the javax and jakarta lines evolve together at `1.0.0-rc.7`, and all
shared logic stays in `upload-file-core` (optional SPI implementations live in the matching store module).

- **additive-first, unchanged**: new capabilities are always new SPIs / methods / properties; no existing
  member is removed and no existing default changes, unless the default is an explicit safety defect
  (G10's "unbounded" is recorded as a breaking default — see the compatibility table).
- **core depends on neither servlet nor Redis**: the `IdentifierLockProvider` / `QuotaStore` interfaces
  live in core, the Redis implementations in `upload-file-store-redis`, and the starter selects them via
  `@ConditionalOnClass` + properties with a fallback (the `metadata-store` detection pattern).
- **Single source of truth**: store metadata (Redis index structure, quota counter) must be
  reconstructible/reconcilable from `TaskStore.list()`; do not introduce a second, drifting truth.
- **Rollback-safe**: no disk-layout / task-metadata format change; the Redis index SET → ZSET change is a
  **one-time lazy migration** (old sets remain readable).
- No `upload-file.*` property is removed; existing defaults are unchanged except G10.

## 3. Task Breakdown

### T35 `RedisTaskStore` index governance + batch reads (store-redis, G7/G8)

- **Files**: `upload-file-store-redis/.../RedisTaskStore.java` (and tests).
- **Approach**:
  - **Index SET → ZSET**: `save()` uses `ZADD indexKey <updateTimeMillis> identifier` (score is always
    `updateTime`); `remove()` uses `ZREM`.
  - **Lazy migration**: on construction/first use, if `TYPE indexKey == set` (old rc.6 data), read
    `SMEMBERS` → `ZADD` (score from each task's actual `updateTime`, else `now`) → `DEL` the old set,
    once.
  - **Batched, pruning `list()`**: `ZRANGE indexKey 0 -1` for identifiers, one **pipeline `MGET`** to read
    them; when `ttlSeconds > 0` first `ZREMRANGEBYSCORE indexKey 0 (now - ttlMillis)` to drop
    certainly-expired index entries; for identifiers whose `MGET` value is `null` (expired key) issue
    `ZREM` (lazy prune). Return the remaining valid tasks.
  - Add a package-private `pruneIndex()` for explicit cleanup/diagnostics; `listIdentifiers()` shares the
    same source.
- **Acceptance**: with `ttl-seconds=1`, write N entries, wait for expiry, then `list()` returns empty and
  `ZCARD indexKey == 0` (leak gone); `list()` does a single pipeline round trip for N tasks (assert via
  mock/`MONITOR` or a counter); old SET data is migrated and readable; existing `RedisTaskStoreTest`
  green.
- **Estimate**: 1.5 person-days.

### T36 Starter consumes a host `UploadErrorRenderer` bean (both starters, G9)

- **Files**: both starters' `UploadFileAutoConfiguration` (`uploadFileServlet` / `downloadFileServlet`
  beans), `README(.zh-CN).md` / `docs/API(.zh-CN).md` / `CHANGELOG(.zh-CN).md`.
- **Approach**: add an `ObjectProvider<UploadErrorRenderer>` parameter to both servlet beans;
  `servlet.setErrorRenderer(provider.getIfAvailable(() -> UploadErrorRenderers.from(properties.getHttp().getErrorBody())))`
  — **host bean wins, otherwise fall back to `legacy`/`standard`**. The plain-Servlet path already has
  `setErrorRenderer`, so nothing changes there. Docs state clearly that on the starter path "providing an
  `UploadErrorRenderer` bean takes effect".
- **Acceptance**: with a host renderer bean both endpoints' failure bodies use the custom envelope;
  without one, behavior is byte-for-byte rc.6; tested on both lines; the README example is
  copy-paste-ready.
- **Estimate**: 0.5 person-day.

### T37 Multipart safe default (both starters, G10)

- **Files**: both starters' `UploadFileAutoConfiguration.multipartConfig(...)`, `UploadFileProperties`,
  configuration-table docs.
- **Approach**: under the `component` strategy, when `max-request-size <= 0` (not explicitly set), derive a
  **bounded** container limit in this order:
  `max-chunk-size > 0 ? max-chunk-size + MULTIPART_OVERHEAD : (max-file-size > 0 ? max-file-size + MULTIPART_OVERHEAD : -1)`
  (`MULTIPART_OVERHEAD` = 1 MB, covering multipart boundaries/headers); log an INFO when derived; if it
  remains `-1` (none of the three set) log a **WARN "container multipart is unbounded — DoS surface"**.
  An explicit `max-request-size` is unchanged. Document the safe default in the config table.
- **Acceptance**: setting only `max-file-size` derives a bounded container limit; none set logs WARN;
  explicit values are byte-for-byte unchanged; tested on both lines.
- **Estimate**: 0.5 person-day.

### T38 `IdentifierLockProvider` SPI + Redis distributed serialization (core + store-redis + both starters, G11)

- **Files**: new core SPIs `IdentifierLockProvider` / `IdentifierLockHandle` / `StripedIdentifierLockProvider`;
  the existing `IdentifierLock` (in-process striped lock) is retained as the `local` implementation base;
  `ResumableUploadService` / `StorageCleanupService` acquire their critical section via the provider;
  `upload-file-store-redis` adds `RedisIdentifierLockProvider`; both starters add selection + properties.
- **Approach**:
  - **SPI (additive)**: `IdentifierLockProvider#lock(String identifier)` returns an `AutoCloseable`
    `IdentifierLockHandle`; services change `synchronized (lockFor(id)) { ... }` to
    `try (IdentifierLockHandle h = identifierLockProvider.lock(id)) { ... }`.
  - **Default unchanged**: the `local` provider wraps the existing `IdentifierLock` (same `Object`
    monitor), so existing constructors/behavior are identical; `StorageCleanupService` and the upload
    service keep sharing one provider instance.
  - **Redis implementation**: `RedisIdentifierLockProvider` acquires with `SET key owner NX PX ttl`,
    releases with an owner-checked `DEL`, and supports configurable `acquire-timeout`/`ttl` (defaults 10s /
    30s) with wait-and-retry; key prefix reuses `key-prefix` (default `upload:lock:`).
  - **Selection**: new `upload-file.lock.identifier-lock=local|redis` (default `local`) +
    `upload-file.lock.acquire-timeout` / `upload-file.lock.ttl`; the starter wires it under
    `@ConditionalOnClass(RedisIdentifierLockProvider)` + `identifier-lock=redis`, falling back to `local`
    with an INFO otherwise.
  - **Documented boundary**: the distributed lock guarantees mutual exclusion only when instances share
    the same disk/object storage; a TTL expiry means the holder died, and `acquire-timeout` bounds the
    retry.
- **Acceptance**: under `local`, existing concurrency/serialization tests are green with zero behavior
  change; under Redis, two service instances serialize upload/merge for the same identifier (concurrent
  integration assertion); timeout and owner release unit-tested; multi-instance docs written.
- **Estimate**: 2.5 person-days.

### T39 `QuotaStore` SPI + atomic quota (core + store-redis + both starters, G12)

- **Files**: new core SPI `QuotaStore` (`long usedBytes()` / `boolean tryReserve(String identifier, long bytes)` /
  `void release(String identifier, long bytes)`) + default `TaskStoreQuotaStore` (reusing the current
  `taskStore.list()` approximation, behavior-equivalent); `ResumableUploadService` routes quota through
  `QuotaStore`; `upload-file-store-redis` adds `RedisQuotaStore` (Lua atomic check-and-incr); both starters
  add selection.
- **Approach**:
  - **Default equivalent**: with nothing configured, use `TaskStoreQuotaStore`, whose `checkQuota`
    semantics match the current implementation (approximate, lock-free).
  - **Atomic implementation**: `RedisQuotaStore` runs a Lua script that atomically reads the counter,
    checks the limit and `INCRBY`s, removing the check-then-act race; `release` uses `DECRBY` (floor 0).
  - **Reconciliation**: provide `reconcile()` to recompute the counter from `TaskStore.list()` (called at
    startup/cleanup) so the counter cannot drift — the concrete realization of the single-source-of-truth
    constraint.
  - **Selection**: new `upload-file.quota.store=task-store|redis` (default `task-store`); the starter wires
    it under `@ConditionalOnClass(RedisQuotaStore)` + `quota.store=redis`, falling back to `task-store`
    with a WARN otherwise.
- **Acceptance**: the default path is byte-for-byte the rc.6 quota cases; the Redis path never exceeds the
  limit under concurrent uploads (concurrent assertion); `reconcile()` corrects a drifted counter from the
  task store; both lines' selection tested.
- **Estimate**: 2 person-days.

### T40 Trusted read API consolidation (core, G13)

- **Files**: new `TrustedUploadService` (read-only trusted facade: the un-gated `getTask` / `getProgress`
  / `isChunkUploaded` variants); the three un-token read methods on `ResumableUploadService` become
  `@Deprecated` and point at the replacement; confirm-phase docs/examples migrate to `TrustedUploadService`.
- **Approach**: remove nothing (the trusted confirm flow is legitimate); make misuse visible at compile
  time via **naming + type**: un-gated reads are exposed only through `TrustedUploadService`
  (`ResumableUploadService.getTaskTrusted(...)` etc. as trusted aliases), and HTTP boundaries always use the
  rc.6 token-gated overloads; the `@Deprecated` old names still compile and run.
- **Acceptance**: `TrustedUploadService` unit-tested; old methods still compile (warnings only); confirm
  docs use the trusted facade; servlet/starter paths unaffected.
- **Estimate**: 0.5 person-day.

### T41 `AccessControl` contract consolidation (core, G14)

- **Files**: new abstract base `AbstractAccessControl` (abstract `decide()`, bridged `check()`) and static
  factories `AccessControl.ofDecide(...)` / `AccessControl.ofCheck(...)`; `AccessControl` Javadoc states
  "you must override one of them".
- **Approach**: do not change the interface default semantics (keep `check()` throwing
  `UnsupportedOperationException` to surface misuse); instead **provide the correct implementation base**:
  extending `AbstractAccessControl` forces `decide()` at compile time, and the functional need is restored
  by `ofDecide` (lambda support no longer relies on `AccessControl` being a functional interface).
- **Acceptance**: an `AbstractAccessControl` subclass that does not implement `decide()` fails to compile;
  `ofDecide`/`ofCheck` bridging unit-tested; the README migration snippet updated to "extend
  `AbstractAccessControl` and override `decide()`".
- **Estimate**: 0.5 person-day.

### T42 Starter wiring optimization + safe-default warning (both starters, G15)

- **Files**: both starters' `UploadFileAutoConfiguration`.
- **Approach**:
  - Add `@Lazy` to the `resumableDownloadService` bean so it is no longer constructed at startup when
    `/download` is off (still created on demand when a host injects it — zero breakage);
  - Startup security check: when `endpoint.enabled=true` and `security.enabled=false` and no host
    `AccessControl` bean exists in the context (i.e. still `PermitAllAccessControl`), log a **WARN "upload
    endpoint has no access control"**; when `security.enabled=true` but the token is empty, keep rc.3's
    fail-fast.
- **Acceptance**: no `ResumableDownloadService` instantiation when `/download` is off (bean-laziness
  assertion); both security combinations' warning/fail-fast tested; both lines mirrored.
- **Estimate**: 0.5 person-day.

### T43 Changelog / roadmap / docs / release (G7–G15 sync + release)

- **Files**: `CHANGELOG(.zh-CN).md`, `docs/ROADMAP(.zh-CN).md`, `docs/DESIGN(.zh-CN).md`,
  `README(.zh-CN).md`, `docs/API(.zh-CN).md`, `docs/PLAN-*` status flip, parent POM and every module POM
  `1.0.0-rc.6 → 1.0.0-rc.7`, demos.
- **Approach**: write the rc.7 release entry (P0/P1 fix list + new SPIs/properties); add an rc.7 upgrade
  notice at the top of the README (multipart safe default, Redis index migration); add
  `IdentifierLockProvider` / `QuotaStore` / `TrustedUploadService` / `AbstractAccessControl` to API/DESIGN;
  flip the PLAN status once all tests pass; run the full `mvn verify` on JDK 17+; publish both lines via
  the existing release profile.
- **Acceptance**: the CHANGELOG itemizes each fix and addition; all modules green; the release announcement
  leads with "`metadata-store=redis` production-trustworthy + multi-instance serialization +
  extension-point consistency".
- **Estimate**: 1 person-day.

## 4. New Configuration & SPI Surface

| Category | Item | Default | Breaking |
| --- | --- | --- | --- |
| Property | `upload-file.lock.identifier-lock` (`local`/`redis`) | `local` | No |
| Property | `upload-file.lock.acquire-timeout` | `10s` | No |
| Property | `upload-file.lock.ttl` | `30s` | No |
| Property | `upload-file.quota.store` (`task-store`/`redis`) | `task-store` | No |
| Property | `multipart.strategy=component` request limit **auto-derived** | bounded (see T37) | **Yes** (was unbounded) |
| SPI | `IdentifierLockProvider` / `IdentifierLockHandle` (new) | `StripedIdentifierLockProvider` (= rc.6 behavior) | No |
| SPI | `QuotaStore` (new) | `TaskStoreQuotaStore` (= rc.6 behavior) | No |
| API | `TrustedUploadService`, `ResumableUploadService.*Trusted` (new) | none | No |
| API | `AbstractAccessControl`, `AccessControl.ofDecide/ofCheck` (new) | none | No |
| Storage | `RedisTaskStore` index SET → ZSET | lazy migration on read | No (old data readable) |

## 5. Compatibility (rc.6 → rc.7)

| Behavior | rc.6 | rc.7 | Notes |
| --- | --- | --- | --- |
| `RedisTaskStore` index | SET, unbounded | ZSET, pruned by `updateTime` | leak fixed; old data lazily migrated |
| `RedisTaskStore.list()` | N+1 `GET` | pipeline `MGET` | equivalent behavior, better performance |
| Starter failure-body rendering | `legacy`/`standard` only | host `UploadErrorRenderer` bean wins | additive; unchanged without a bean |
| multipart `component` request limit | unset = unbounded | unset = bounded (derived from chunk/file) | **breaking default**; explicit values unchanged; WARN added |
| Multi-instance upload/merge | in-process lock | `identifier-lock=redis` can serialize across instances | additive; default `local` unchanged |
| Global quota | approximate, non-atomic | default unchanged; `quota.store=redis` atomic | additive |
| Trusted reads | public, un-gated | un-gated consolidated into `TrustedUploadService`; old names `@Deprecated` | additive; old calls compile |
| `AccessControl` implementation | interface default methods | new `AbstractAccessControl` base | additive; interface semantics unchanged |
| Success body / endpoints / existing `upload-file.*` keys / disk & metadata format | — | unchanged | — |

- No new artifact; core manual wiring still works and is unaffected unless the new SPIs are used.
- No disk-layout / task-metadata format change; the Redis index migration is automatic on first
  read/write, and a compatibility window for the old index is recommended before rolling back to rc.6.

## 6. Test Plan

- store-redis: index leak (`ZCARD==0` after TTL expiry), batched `list()` round trips, old SET index lazy
  migration, `RedisQuotaStore` concurrency stays under the limit, `RedisIdentifierLockProvider` two-instance
  mutual exclusion (reusing `RedisDockerRule`).
- core: `QuotaStore` default equivalence and `reconcile()`, `IdentifierLockProvider` default equivalence,
  `TrustedUploadService`, `AbstractAccessControl` / `ofDecide` / `ofCheck` bridging.
- starter (javax + jakarta, mirrored): `UploadErrorRenderer` bean precedence, multipart derivation and
  WARN, `@Lazy` download service, safe-default warning, new-property selection and fallback.
- Compatibility regression: byte-for-byte assertions that the default paths are unchanged from rc.6; the
  explicit `multipart` path unchanged.
- Full reactor `mvn verify` on JDK 17+; keep JaCoCo in every module.

## 7. Documentation & Examples

- `README(.zh-CN).md`: rc.7 upgrade notice (multipart safe default, Redis index migration), how the
  `UploadErrorRenderer` bean takes effect, multi-instance serialization and atomic-quota config, the
  `AbstractAccessControl` migration snippet.
- `docs/API(.zh-CN).md`: failure-body renderer precedence (host bean > `legacy`/`standard`).
- `docs/DESIGN(.zh-CN).md`: `IdentifierLockProvider`, `QuotaStore`, `TrustedUploadService`,
  `AbstractAccessControl`.
- `docs/ROADMAP(.zh-CN).md` / `CHANGELOG(.zh-CN).md`: rc.7 plan entry → flip after release; the P2-4
  ecosystem items and P2-1/P2-2 are deferred to rc.8.

## 8. Milestones & Release

1. **M1** (T35, T36, T37): store correctness + extension-point consistency + safe default — top priority,
   independently shippable;
2. **M2** (T38): multi-instance identifier serialization (SPI + Redis implementation);
3. **M3** (T39): atomic quota (SPI + Redis implementation);
4. **M4** (T40, T41): trusted API consolidation + `AccessControl` base;
5. **M5** (T42): starter wiring optimization + security warning;
6. **M6** (T43): version `1.0.0-rc.6 → 1.0.0-rc.7`, full `mvn verify` on JDK 17+, CHANGELOG/ROADMAP sync
   and release.

## 9. Out of Scope (Deferred to rc.8)

- **P2-1 Audit context**: `AccessControlListener` carrying method/IP/UA (needs a lightweight core↔servlet
  context pass-through; separate design).
- **P2-2 access-log de-noising**: aggregate/sample by identifier, only deny and task-level events (same
  domain as P2-1; design together).
- **P2-4 Ecosystem items**: Prometheus metrics, upload-complete Webhook, content-addressed instant upload,
  whole-file SHA-256 verification, object-storage backend, tus protocol, virus-scan hook (reserve extension
  points in `ChunkStorage` / merge-complete events) — progressed per the ROADMAP.
