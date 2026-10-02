# V1.0.0-rc.8 Task Plan (Final pre-GA Closure: quota/lock correctness + audit context + release engineering)

> 🇨🇳 [中文](PLAN-V1.0.0-rc.8.zh-CN.md)
>
> rc.7 closed every P0/P1 in the [rc.6 migration feedback](../doc/user-feedback/upload-file-rc6-migration-feedback.md)
> and made `metadata-store=redis` production-trustworthy. But the rc.7 usage feedback
> ([upload-file-rc7-usage-feedback.md](../doc/user-feedback/upload-file-rc7-usage-feedback.md)) found three
> correctness defects in the **newly introduced multi-instance / quota capability**: `RedisQuotaStore` has no
> automatic reconciliation, merged-but-unconfirmed tasks leak quota forever, and `RedisIdentifierLockProvider`
> never renews its lease. Meanwhile the **audit context** and **access-log noise** deferred from the rc.6
> feedback are still open.
>
> **rc.8 is the last rc before the `1.0.0` GA**: it only does "correctness closure + audit compliance + GA
> release engineering" and adds no large new feature. After this release the **API is frozen**; `1.0.0` only
> bumps the version and publishes the announcement.
>
> ✅ **Status: implemented and released in `1.0.0-rc.8` (2026-09-15).** T44–T53 are all done: quota/lock
> correctness closure (auto-reconcile, merged-unconfirmed reclaim, lease renewal, atomic migration, batched
> `list()`), audit context `AccessContext`, `access-log` noise reduction, `TrustedUploadService` auto-wiring,
> the unified-envelope example, `upload-file-bom`, the V1.0.0 SOW / API freeze and the binary-compat gate —
> see the [Changelog](../CHANGELOG.md).
>
> Feedback sources: `doc/user-feedback/upload-file-rc7-usage-feedback.md` (§5 P1-1/P1-2/P1-3, P2-1/P2-2/P2-3/P2-4,
> §4-2); historical `doc/user-feedback/upload-file-rc6-migration-feedback.md` (§5 P2-1/P2-2),
> `doc/user-feedback/upload-file-usage-feedback.md`.

## 1. Goal and Scope

**Theme**: take rc.7's "multi-instance + atomic quota" from *implemented* to *correct*, complete the audit
context required for enterprise compliance, and finish the GA release engineering (BOM, SOW, binary-compat
gate). rc.8 freezes the API and adds no new external selling point.

| ID | Gap | Description | rc.8 task |
| --- | --- | --- | --- |
| G16 | `RedisQuotaStore` has no automatic reconciliation | `reconcile(TaskStore)` (`RedisQuotaStore.java:113`) has **no production caller** besides tests; the starter (`UploadFileAutoConfiguration.java:234-246`) only `create`s it, so after Redis data loss the quota is underestimated | T44 |
| G17 | Quota for merged-but-unconfirmed tasks leaks forever | `StorageCleanupService.java:241` `continue`s on `isMerged()`, and `quotaStore.release` only fires on cancel/expiry deletion; once a merged task's key TTLs out, its per-identifier count stays in `total` forever | T44 |
| G18 | Distributed lock never renews | `RedisIdentifierLockProvider.java:73` uses a fixed `SET NX PX ttl`; `merge()` holds it without renewal, so a long merge past `lock.ttl` (default 30s) loses the lock and breaks cross-instance serialization | T45 |
| G19 | Index lazy migration is not atomic | `RedisTaskStore.ensureIndexMigrated` runs `SMEMBERS → DEL → ZADD` (`RedisTaskStore.java:92-107`); concurrent startup/`save()` during migration can drop entries | T46 |
| G20 | `list()` single `MGET` blocks at large N | `RedisTaskStore.list()` issues one full `MGET` on a single connection (`RedisTaskStore.java:173-178`), occupying the connection and Redis single thread for very large indexes | T46 |
| G21 | Audit hook lacks request context | `AccessControlListener.onDecision(identifier, action, decision, elapsedNanos)` (`AccessControlListener.java:28`) has no method/IP/UA, so persisted audit fields differ from login audit | T47 |
| G22 | `access-log` is per-chunk | `gate()` notifies listeners on every chunk; log volume = chunk count (500MB/5MB = 100 lines/file) | T48 |
| G23 | `TrustedUploadService` is not auto-wired | The core read-only facade (rc.7) has no starter bean, so a host must write its own config class to use trusted reads | T49 |
| G24 | No minimal unified-envelope example | The `UploadErrorRenderer` bean already wins (rc.7), but there is no "host bean → unified envelope" doc example | T50 |
| G25 | No BOM | Hosts must align 7 artifact versions by hand, easy to drift | T51 |
| G26 | No V1.0.0 SOW / API-freeze declaration | rc.3 plan task T13 required `docs/PLAN-V1.0.0.md`, which is missing; GA has no freeze basis | T52 |
| G27 | Release engineering not GA-grade | No binary-compat gate, SBOM, reproducible build, or Central checklist | T53 |

## 2. Architecture and Constraints

rc.8 **adds 1 Maven artifact** (`upload-file-bom`, `dependencyManagement` only, no code); the javax and
jakarta lines still evolve together at the same `1.0.0-rc.8`, sharing logic in `upload-file-core`.

- **additive-first unchanged**: new capabilities are new SPI / default methods / new methods / new properties;
  no member is removed and no functional default changes. Only two controlled default changes
  (`access-log-scope` log volume and `quota.store=redis` reconciliation), listed in the compatibility table.
- **core depends on neither servlet nor Redis**: `QuotaStore.reconcile` is declared in core (`default` no-op);
  the Redis implementation and lease renewal live in `upload-file-store-redis`; the audit-context carrier is in
  core, and the Servlet layer only fills it.
- **Single source of truth**: the quota counter must be rebuildable from `TaskStore.list()`; rc.8 makes
  reconciliation **automatic** instead of relying on an operator.
- **Rollback-safe**: no disk-layout / task-metadata format change; no Redis index structure change (only the
  migration becomes atomic).
- **API freeze**: after rc.8 is merged, the public API and the `upload-file.*` property surface are frozen;
  `1.0.0` changes no code.

## 3. Tasks

### T44 Automatic quota reconciliation + merged-unconfirmed reclaim (core + store-redis + both starters, G16/G17)

- **Files**: `upload-file-core/.../store/QuotaStore.java` (add `default void reconcile(TaskStore)`),
  `TaskStoreQuotaStore` (no-op, default-equivalent), `RedisQuotaStore` (`reconcile` becomes `@Override`),
  `StorageCleanupService` (reclaim quota in the orphan scan + optional periodic reconcile), both starters'
  `UploadFileAutoConfiguration` (startup reconcile), `README`/`API` docs.
- **Design**:
  - **SPI closure**: add `default void reconcile(TaskStore taskStore) {}` to `QuotaStore`; the default is empty
    (`TaskStoreQuotaStore` already derives from the task store); `RedisQuotaStore` overrides its existing logic.
  - **Startup reconcile**: after the `QuotaStore` bean is created and before `ResumableUploadService` is usable,
    the starter calls `quotaStore.reconcile(taskStore)` once (effective only for non-default implementations,
    i.e. `quota.store=redis`), correcting both the underestimate after Redis loss and the overestimate from
    stale hashes.
  - **Cleanup reclaim (G17)**: when `StorageCleanupService.cleanupOrphans` deletes a chunk/merged directory with
    no task record, it also calls `quotaStore.release(identifier)`; a merged-but-unconfirmed identifier whose
    task key has TTL'd out gets both its on-disk directory and its quota reservation reclaimed.
    `cleanupExpiredTasks` keeps skipping `isMerged()` (merged artifacts are handled by the orphan scan) but gets
    a comment explaining the reclaim path.
  - **Fallback TTL (optional)**: give the `RedisQuotaStore` usage hash a whole-key TTL aligned with
    `cleanup.task-ttl` (or a time-scored ZSET) as a last resort when reconcile has not run; off by default,
    documented.
  - **Docs**: state that the `quota.store=redis` counter is rebuildable via `reconcile`, that startup reconcile
    is on by default, and that `TaskStoreQuotaStore` is stateless and cannot drift.
- **Acceptance**: with `quota.store=redis`, wiping the Redis counter then starting restores `usedBytes()` to match
  the task store; after "merged but unconfirmed + task expired", one cleanup run brings `usedBytes()` to zero with
  no false 507; the default `task-store` path is byte-for-byte unchanged from rc.7; tested on both lines.
- **Estimate**: 1.5 person-days.

### T45 `RedisIdentifierLockProvider` lease-renewal watchdog (store-redis, G18)

- **Files**: `upload-file-store-redis/.../RedisIdentifierLockProvider.java` (+ tests),
  `UploadFileProperties` `lock.*` (optional new `renew-interval`), `README`/`DESIGN` docs.
- **Design**:
  - **Renew while held**: the `IdentifierLockHandle` returned by `lock()` starts a lightweight watchdog
    (daemon thread or `ScheduledExecutorService`, every `ttl/3`) that renews with a Lua script
    ("`PEXPIRE` only while the owner token still matches"); `close()` stops the watchdog first, then
    owner-checked `DEL`.
  - **Defaults/config**: renewal period defaults to `ttl/3`, overridable via `upload-file.lock.renew-interval`;
    `ttl` stays 30s by default. If renewal fails (key already reassigned) log a WARN and stop renewing, letting
    `acquire-timeout` semantics converge.
  - **Documented magnitude**: `lock.ttl` must exceed "worst-case critical-section time / 3"; give recommended
    values for large-file `merge` (e.g. `ttl >= 120s` for 500MB on slow disks) and note that renewal makes long
    merges safe.
  - **local unchanged**: `StripedIdentifierLockProvider` needs no renewal (in-process monitor never times out).
- **Acceptance**: with `ttl=1s`, hold the lock through a critical section longer than `ttl` and assert a second
  instance cannot acquire it during the hold; it becomes acquirable right after `close()`; a former owner never
  deletes a reassigned lock; unit tests + a two-instance integration test (`RedisDockerRule`).
- **Estimate**: 1.5 person-days.

### T46 `RedisTaskStore` atomic migration + batched `list()` (store-redis, G19/G20)

- **Files**: `upload-file-store-redis/.../RedisTaskStore.java` (+ tests).
- **Design**:
  - **Atomic migration (G19)**: rewrite `ensureIndexMigrated` as a Lua script that atomically does
    `TYPE` check → `SMEMBERS` → `ZADD` (score from each task's actual `updateTime`, else `now`) → `DEL` in Redis's
    single thread; or `RENAME` to a temp key before migrating, so a concurrent `save()` inside the
    `SMEMBERS → DEL → ZADD` window cannot be lost. Mark completion (a sentinel field/key) to avoid re-migration.
  - **Batched `list()` (G20)**: run `MGET` in batches (default 500, constant-configurable), or `ZSCAN` for very
    large indexes; keep "consistent results within one cleanup pass" (take a `ZRANGE` snapshot, then read in
    batches).
  - No index-structure change (still ZSET) and no return-semantics change.
- **Acceptance**: concurrent migration + concurrent `save()` loses no identifier (multi-thread assertion);
  `list()` at N=10k reads in batches and returns everything; old SET indexes still lazy-migrate; existing
  `RedisTaskStoreTest` green.
- **Estimate**: 1 person-day.

### T47 Audit context `AccessContext` (core + both servlets + both starters, G21)

- **Files**: new core `AccessContext` (immutable: `method`/`uri`/`remoteAddr`/`userAgent`) and
  `AccessContextHolder` (ThreadLocal, empty context by default); `AccessControlListener` gains a **default**
  overload `onDecision(AccessContext, identifier, action, decision, elapsedNanos)` (bridges to the old 5-arg
  method by default, so existing implementations stay compatible); core services call the new overload;
  `UploadServlet`/`DownloadServlet` (javax + jakarta) fill `AccessContextHolder` on entry and clear it in
  `finally`; the starter's access-log listener consumes the context.
- **Design**:
  - **additive**: the old 5-arg `onDecision` remains an interface method (still implementable); the new 6-arg
    method is `default`, so existing host listeners (e.g. PathFinder's `UploadAccessAuditListener`) **compile
    unchanged** and only override the new method to get context.
  - **Context source**: the Servlet layer reads method/URI/remoteAddr/User-Agent from `HttpServletRequest`;
    MVC/plain-core calls without context get an empty object with `null` fields, never an exception.
  - **Audit persistence**: `docs/API` gives a minimal "override the 6-arg method → persist method/uri/ip/ua"
    example.
- **Acceptance**: on the Servlet path the listener receives full method/IP/UA; the core manual-wiring path
  receives an empty context without error; an existing 5-arg-only listener compiles and behaves unchanged;
  tested on both lines.
- **Estimate**: 1.5 person-days.

### T48 `access-log` noise reduction (core + both servlets + both starters, G22)

- **Files**: `UploadFileProperties.Observability` (new `access-log-scope`),
  `UploadFileAutoConfiguration.uploadFileAccessLogListener` (both lines), `UploadFileContext` (plain-Servlet
  init-param), `README`/`API` docs.
- **Design**:
  - New `upload-file.observability.access-log-scope = task | deny | all`:
    - `deny`: only deny decisions;
    - `task` (**new default**): deny + task-level events (first chunk, merge/mergeStatus, download, cancel),
      skipping `action=chunk` allows;
    - `all`: the rc.7 behavior (one line per decision).
  - Filter by scope when `access-log=true`; when `access-log=false` no listener is wired (unchanged).
  - The plain-Servlet path aligns via the `observability.access-log-scope` init-param.
- **Acceptance**: with `access-log=true` and the default scope, a 500MB/5MB upload produces task-level logs only
  (not 100 lines); `all` matches rc.7 line-for-line; `deny` logs denials only; both lines mirrored.
- **Estimate**: 0.5 person-days.

### T49 Auto-wire `TrustedUploadService` in the starter (both starters, G23)

- **Files**: both starters' `UploadFileAutoConfiguration`, `README`/`API` docs.
- **Design**: add `@Bean @ConditionalOnMissingBean TrustedUploadService`, built from the `TaskStore` (read-only
  facade, no gate); Javadoc and docs state "**for trusted server-side flows only**, never expose at the HTTP
  boundary"; hosts may override the bean. Provide a `@ConditionalOnProperty` switch (on by default) for hosts
  that do not want the bean exposed.
- **Acceptance**: with the starter, `TrustedUploadService` is directly injectable and a PathFinder-like host can
  delete its own config class; host overrides work; both lines tested.
- **Estimate**: 0.5 person-days.

### T50 Unified response-envelope example (docs + demo, G24)

- **Files**: `README(.zh-CN).md`, `docs/API(.zh-CN).md`, `example/upload-file-boot4-demo`.
- **Design**: add a minimal "host provides an `UploadErrorRenderer` bean → render
  `ApiResponse{code,message,data}`" example (rc.7 already prefers a host bean; no Servlet override needed); add a
  unified-envelope config under the demo's `enterprise` profile, demonstrating end-to-end that the success body
  is unchanged while the failure body uses the unified envelope.
- **Acceptance**: the example is copy-paste usable; the running demo shows `/upload` failures in the unified
  envelope; docs state that the component's success body stays bare JSON and only the error body can be unified.
- **Estimate**: 0.5 person-days.

### T51 `upload-file-bom` module (release engineering, G25)

- **Files**: new `upload-file-bom/pom.xml` (`packaging=pom`, `dependencyManagement` importing the 7 library
  modules); parent POM `<modules>` and `<dependencyManagement>` include the BOM; `README` quick start switches to
  `import` the BOM and specify `artifactId` only (no version).
- **Design**: the BOM covers `upload-file-core`, `upload-file-servlet`, `upload-file-servlet-jakarta`,
  `upload-file-spring-boot-starter`, `upload-file-spring-boot-starter-jakarta`, `upload-file-store-jdbc`,
  `upload-file-store-redis`; versions are managed by `${project.version}`; the BOM itself has
  `maven.deploy.skip=false`.
- **Acceptance**: a host that only `import`s the BOM plus the needed artifact (no version) resolves and builds;
  `mvn deploy` publishes the BOM; module versions match the BOM.
- **Estimate**: 0.5 person-days.

### T52 V1.0.0 SOW + API-freeze declaration + javax downgrade policy (docs, G26)

- **Files**: new `docs/PLAN-V1.0.0.zh-CN.md` + `docs/PLAN-V1.0.0.md` (SOW, filling the rc.3 T13 gap);
  `README`/`ROADMAP` release policy.
- **Design**: the SOW states:
  - **GA scope**: the capability surface frozen at rc.8, listing the public API / SPI / `upload-file.*` properties;
  - **API freeze**: frozen at rc.8 merge; semantic versioning from `1.0.0`; `@Deprecated` members (e.g.
    `ResumableUploadService.getTask(id)`) are **kept**, planned for removal in 2.0;
  - **javax downgrade**: from GA, `upload-file-servlet` / `upload-file-spring-boot-starter` (javax) are marked
    `maintenance / deprecated` (security fixes only), converging to a jakarta-only line at 2.0;
  - **Upgrade/rollback matrix**: rc.7 → rc.8 → 1.0.0 config and behavior deltas, rollback steps;
  - **Compatibility promise**: no disk-layout / metadata-format change, no breaking API.
- **Acceptance**: SOW review passes; README release policy matches the freeze declaration; zh/en in sync.
- **Estimate**: 1 person-day.

### T53 GA-grade release engineering + docs/version/publish (G27)

- **Files**: parent POM (`japicmp-maven-plugin` or `revapi`, `cyclonedx-maven-plugin`,
  `project.build.outputTimestamp`), `.github/workflows`, `CHANGELOG(.zh-CN).md`, `docs/ROADMAP(.zh-CN).md`,
  `docs/DESIGN(.zh-CN).md`, `README(.zh-CN).md`, `docs/API(.zh-CN).md`, each module POM
  `1.0.0-rc.7 → 1.0.0-rc.8`, demo.
- **Design**:
  - **Binary-compat gate**: CI runs japicmp/revapi against the rc.7 baseline; any non-additive change fails the
    build;
  - **SBOM + reproducible**: wire CycloneDX SBOM and `outputTimestamp`;
  - **Central checklist**: verify GPG, sources/javadoc jars, `maven.deploy.skip` (fixed in rc.7), and the
    `-P release` staging checks;
  - **Docs**: CHANGELOG entry for rc.8 (quota/lock correctness + audit context + BOM + freeze); ROADMAP marks
    rc.8 as the last pre-GA rc and registers GA; DESIGN adds `AccessContext`, renewal, reconciliation; README top
    adds rc.8 upgrade notes (`access-log-scope` default, quota reconciliation);
  - **Publish**: full JDK 17+ `mvn verify` → version `1.0.0-rc.8` → publish both lines + BOM via the release profile.
- **Acceptance**: japicmp green against rc.7; SBOM generated; all modules green; the announcement highlights
  "multi-instance + quota correctness closure, audit context, GA-ready".
- **Estimate**: 1.5 person-days.

## 4. New Configuration and SPI Surface

| Kind | Item | Default | Breaking |
| --- | --- | --- | --- |
| SPI | `QuotaStore.reconcile(TaskStore)` (new `default`) | no-op (`TaskStoreQuotaStore`) | No |
| Property | `upload-file.lock.renew-interval` | `ttl/3` | No |
| Property | `upload-file.observability.access-log-scope` (`task`/`deny`/`all`) | `task` | **Yes** (log volume; `all` restores old) |
| Property | `upload-file.quota.store=redis` behavior | startup auto-reconcile | No (corrects under/over-count) |
| API | `AccessContext` / `AccessContextHolder` (new) | empty context | No |
| API | `AccessControlListener.onDecision(AccessContext, ...)` (new `default` overload) | bridges the old 5-arg method | No |
| API | `TrustedUploadService` starter bean (new, `@ConditionalOnMissingBean`) | auto-wired | No |
| Artifact | `upload-file-bom` (new, `packaging=pom`) | — | No |

## 5. Compatibility (rc.7 → rc.8)

| Behavior | rc.7 | rc.8 | Notes |
| --- | --- | --- | --- |
| `QuotaStore` (`task-store` default) | approximate, task-store-derived | unchanged | `reconcile` default no-op |
| `quota.store=redis` | may drift; merged-unconfirmed leak | startup reconcile + cleanup reclaim | fixes under/over-count and leak |
| `RedisIdentifierLockProvider` | fixed TTL, no renewal | watchdog renewal (`ttl/3`) | long merge no longer loses the lock; `local` unchanged |
| `RedisTaskStore` index migration | non-atomic `SMEMBERS→DEL→ZADD` | atomic Lua/`RENAME` | no entry loss under concurrent migration |
| `RedisTaskStore.list()` | single `MGET` | batched `MGET`/`ZSCAN` | same result, safer at large N |
| Audit listener | 5-arg `onDecision` (no request context) | new 6-arg `default` overload | additive; old impls unchanged |
| `access-log` output | one line per decision | default `task`-level; `all` restores | **breaking-default** (log volume only) |
| `TrustedUploadService` | host-built | starter `@ConditionalOnMissingBean` | additive; overridable/switchable |
| Success body / endpoints / disk & metadata formats / existing property keys | — | unchanged | — |

- Adds the `upload-file-bom` artifact; other artifact coordinates and existing `upload-file.*` keys are
  unchanged; core manual wiring still works.
- No disk-layout / task-metadata format change; no Redis index structure change.

## 6. Test Plan

- store-redis: quota reconcile recovery, merged-unconfirmed reclaim (`usedBytes()==0` after cleanup), renewal
  mutual exclusion across a long critical section (`RedisDockerRule`, two instances), atomic migration with no
  lost entries under concurrency, batched `list()` completeness at N=10k.
- core: `QuotaStore.reconcile` default no-op equivalence, `AccessContext`/`AccessContextHolder` empty context,
  `AccessControlListener` 6-arg `default` bridge, `cleanupOrphans` quota reclaim.
- starter (javax + jakarta, mirrored): startup reconcile call, `access-log-scope` three values,
  `TrustedUploadService` wiring/override, `UploadErrorRenderer` unified-envelope example.
- Compatibility regression: default path byte-for-byte unchanged from rc.7; `access-log-scope=all` matches rc.7
  line-for-line.
- Release: japicmp green against the rc.7 baseline; full JDK 17+ reactor `mvn verify`; JaCoCo per module.

## 7. Documentation and Examples

- `README(.zh-CN).md`: rc.8 upgrade notes (`access-log-scope` default, `quota.store=redis` auto-reconcile), BOM
  quick start, `TrustedUploadService` auto-wiring, unified-envelope example, GA freeze and javax downgrade notes.
- `docs/API(.zh-CN).md`: `AccessContext` and the 6-arg listener, `access-log-scope`, `QuotaStore.reconcile`,
  envelope renderer precedence.
- `docs/DESIGN(.zh-CN).md`: lock renewal, quota reconcile/reclaim, atomic index migration, audit-context
  propagation.
- `docs/ROADMAP(.zh-CN).md` / `CHANGELOG(.zh-CN).md`: rc.8 plan entry → flip on release; register GA; P2-5
  ecosystem items move to post-GA 1.0.x/1.1.
- New `docs/PLAN-V1.0.0(.zh-CN).md` (SOW).

## 8. Milestones and Release

1. **M1** (T44, T45, T46): quota / lock / storage correctness closure — highest priority, GA hard gate;
2. **M2** (T47, T48): audit context + access-log noise reduction — enterprise compliance;
3. **M3** (T49, T50): `TrustedUploadService` auto-wiring + unified-envelope example — integration experience;
4. **M4** (T51, T52): BOM + SOW / API freeze — GA release engineering;
5. **M5** (T53): binary-compat gate / SBOM / version `1.0.0-rc.7 → 1.0.0-rc.8`, full `mvn verify`,
   CHANGELOG/ROADMAP sync and publish;
6. **M6 (GA)**: after rc.8 freezes, version `1.0.0-rc.8 → 1.0.0`, version bump and announcement only, no code.

## 9. Out of Scope (post-GA 1.0.x / 1.1)

- **Ecosystem (ROADMAP P2-5)**: Prometheus metrics, upload-completed webhook, content-addressed instant upload,
  whole-file SHA-256, object-storage backend, tus protocol, virus-scan hook — per the ROADMAP.
- **Per-user quota / rate limiting, multi-tenant namespaces, recycle bin / versioning, pre-signed downloads** —
  scheduled after GA as needed.
- **javax line convergence**: maintenance from GA, removed at 2.0 (policy declared in T52).

## 10. GA Entry Criteria

After rc.8 is merged, `1.0.0` may be released if and only if:

1. rc.7 feedback **P1-1 / P1-2 / P1-3 are all closed** (T44/T45) and P2 items are handled as above;
2. rc.6 feedback's deferred **P2-1 / P2-2 are closed** (T47/T48);
3. the `docs/PLAN-V1.0.0` SOW passes review and the **API and property surface is frozen** (T52);
4. japicmp is green against the rc.7 baseline and the BOM is publishable (T51/T53);
5. full JDK 17+ reactor `mvn verify` is green.
