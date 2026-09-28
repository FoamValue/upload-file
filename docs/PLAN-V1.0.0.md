# V1.0.0 Statement of Work (SOW) and API Freeze

> 🇨🇳 [中文](PLAN-V1.0.0.zh-CN.md)
>
> This is the Statement of Work for the `1.0.0` GA. After rc.8 is merged the **public API and the
> `upload-file.*` property surface are frozen**; `1.0.0` is a version bump and announcement only,
> with no code change. From `1.0.0` the project follows [semantic versioning](https://semver.org/):
> breaking changes may only land in `2.0.0`. `1.0.0-rc.9` is the **security closure** inside the
> freeze: additive changes only (new `require-checksum` property) plus tightened defaults
> (`max-chunk-size` defaults to 10 MB; startup fails when the request/chunk/file limits are all
> unbounded) — no frozen contract is broken (see §3).

## 1. GA Scope

The capability surface frozen at rc.8. GA provides:

- **Chunked upload / resumable upload**: `POST /upload` (chunk), `action=merge`, `action=mergeAsync`,
  `action=mergeStatus`, `action=cancel`, `action=progress`;
- **Resumable download**: `GET /download` (`Range` → 206/416);
- **Metadata stores**: memory / file / JDBC / Redis (`upload-file.metadata-store`);
- **Multi-instance capabilities**: distributed identifier lock, atomic quota, cleanup lease,
  Redis index governance;
- **Security & compliance**: `AccessControl` (distinct `401`/`403`), `AccessControlListener` +
  request context `AccessContext`, unified failure bodies (`legacy`/`standard`/host bean);
- **Observability**: `observability.log-stats`, `observability.access-log` + `access-log-scope`;
- **Two servlet lines**: `javax` (Servlet 4 / Boot 2.7) and `jakarta` (Servlet 6 / Boot 4).

## 2. Frozen Public API

### Core SPI / facades (`upload-file-core`)

| Type | Purpose | Frozen |
| --- | --- | --- |
| `TaskStore` | task metadata store SPI (`get/save/remove/list`) | yes |
| `ChunkStorage` | chunk storage SPI | yes |
| `QuotaStore` | global capacity quota SPI (incl. `reconcile(TaskStore)` default) | yes |
| `IdentifierLockProvider` / `IdentifierLockHandle` | per-identifier serialization SPI | yes |
| `CleanupLock` | cleanup lease SPI | yes |
| `AccessControl` | access-decision SPI (`decide(...)` new entry, `check(...)` `@Deprecated`) | yes |
| `AccessControlListener` | decision listener (5-arg + 6-arg `default` overload) | yes |
| `AccessContext` / `AccessContextHolder` | audit request context (new in rc.8) | yes |
| `UploadErrorRenderer` | failure-body SPI | yes |
| `ResumableUploadService` / `ResumableDownloadService` | core services | yes |
| `TrustedUploadService` | trusted (un-gated) read-only facade | yes |
| `StorageCleanupService` | expired-task / orphan cleanup | yes |
| `TaskStoreMigrator` | metadata migration helper | yes |

### Store implementations (`upload-file-store-jdbc` / `upload-file-store-redis`)

The public constructors and static factories of `JdbcTaskStore`, `RedisTaskStore`,
`RedisQuotaStore`, `RedisIdentifierLockProvider` and `RedisCleanupLock` are frozen.

### HTTP layer (`upload-file-servlet` / `upload-file-servlet-jakarta`)

The endpoint contracts of `UploadServlet` / `DownloadServlet`, their failure-body shapes and the
overridable-bean contracts (`uploadFileServlet`, `uploadFileServletRegistration`, ...) are frozen.

## 3. Frozen Configuration (`upload-file.*`)

`storage-dir`, `metadata-dir`, `metadata-store`, `verify-checksum`, `upload-url`, `download-url`,
`max-chunk-size`, `max-request-size`, `max-file-size`, `merge.*`, `cleanup.*`, `async-merge.*`,
`endpoint.*`, `http.*`, `multipart.*`, `security.*`, `quota.*`, `observability.*`, `migration.*`,
`jdbc.*`, `redis.*`, `lock.*` (incl. rc.8 `lock.renew-interval`).

Added/changed in rc.8:

| Item | Default | Notes |
| --- | --- | --- |
| `lock.renew-interval` | `ttl/3` | distributed-lock renewal cadence; `0` derives `ttl/3` |
| `observability.access-log-scope` | `task` | `task`/`deny`/`all`; `all` restores the rc.7 per-decision log (**default change, log volume only**) |
| `quota.store=redis` | startup reconcile | corrects Redis data loss (under-count) and leaked reservations (over-count) |
| `trusted-upload-service.enabled` | `true` | whether to expose the `TrustedUploadService` bean |

Added/changed in rc.9 (security closure, additive, part of the frozen surface):

| Item | Default | Notes |
| --- | --- | --- |
| `require-checksum` | `false` | with `verify-checksum + require-checksum`, a chunk missing `chunkMd5` is rejected and deleted (new property) |
| `max-chunk-size` | `10 MB` | default tightened from `-1` (unlimited) to 10 MB (breaking-default, default value only; explicit config is unaffected) |
| request/chunk/file limits | fail-fast when all unbounded | unset `max-request-size` is derived from `max-chunk-size`/`max-file-size` (+1 MB); startup fails when all three are unbounded (new behavior) |

## 4. `@Deprecated` Retention

The following members are retained from `1.0.0` and **not removed**; removal is planned for `2.0.0`:

- `ResumableUploadService.getTask(String)` (un-gated) → use `getTask(String, String)` or
  `TrustedUploadService.getTask(String)`;
- `ResumableUploadService.isChunkUploaded(String, int)` (un-gated) → use the token overload or
  `TrustedUploadService`;
- `AccessControl.check(...)` → override `decide(...)`.

## 5. javax Line Downgrade Policy

- `upload-file-servlet` and `upload-file-spring-boot-starter` (javax / Boot 2.7) are marked
  **maintenance / deprecated** from GA: security fixes only, no new features.
- `2.0.0` converges to a single **jakarta** line (`upload-file-servlet-jakarta` /
  `upload-file-spring-boot-starter-jakarta`).
- Migration path: after upgrading to Boot 3.x/4.x, swap the coordinates for the `-jakarta`
  artifacts; package names are unchanged, only `javax.*` → `jakarta.*` (handled by the
  container/framework).

## 6. Upgrade / Rollback Matrix (rc.7 → rc.8 → 1.0.0)

| Behavior | rc.7 | rc.8 / 1.0.0 | Rollback |
| --- | --- | --- | --- |
| `quota.store=redis` counter | may drift / leak | startup reconcile + cleanup reclaim | set `quota.store=task-store` (stateless) |
| distributed identifier lock | fixed TTL, no renewal | watchdog renewal (`ttl/3`) | set `lock.renew-interval` or `lock.identifier-lock=local` |
| `RedisTaskStore` index migration | non-atomic | Lua atomic migration | none (idempotent) |
| `RedisTaskStore.list()` | single `MGET` | batched `MGET` | none (equivalent result) |
| audit listener | 5-arg (no context) | new 6-arg `default` overload | old 5-arg impls need no change |
| `access-log` output | one line per decision | `task` level by default | set `access-log-scope=all` |
| `TrustedUploadService` | host-provided | starter auto-wiring | set `trusted-upload-service.enabled=false` or override the bean |

**Rollback steps**: `1.0.0 → rc.8` needs no config change; `rc.8 → rc.7` restores per the table
above (`access-log-scope=all`, optionally `quota.store=task-store`, `lock.identifier-lock=local`).

## 7. Compatibility Commitments

- **No breaking API**: every rc.8 change over rc.7 is additive (new SPI default methods / methods /
  properties / artifact); the only controlled default change is `access-log-scope` log volume,
  reversible with `all`.
- **No disk layout / task metadata format change**: chunk dirs, merged dirs and the `UploadTask`
  JSON are unchanged.
- **No Redis index structure change**: still a `ZSET`; only the migration is made atomic.
- **Binary-compatibility gate**: CI runs revapi against the rc.7 baseline; any non-additive change
  fails the build (see the root `pom.xml` `compat-check` profile).

## 8. GA Admission Criteria

`1.0.0` may be released if and only if:

1. rc.7 feedback P1-1/P1-2/P1-3 are closed (T44/T45);
2. rc.6 feedback deferred P2-1/P2-2 are closed (T47/T48);
3. this SOW is reviewed and the API/property surface is frozen;
4. the binary-compatibility gate is green against the rc.7 baseline and `upload-file-bom` is
   publishable;
5. the full JDK 17+ reactor `mvn verify` is green.
