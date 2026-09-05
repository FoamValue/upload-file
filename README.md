# upload-file

Maven toolkit for **large-file chunked upload / resumable (breakpoint) upload / HTTP `Range` resumable download**. Pure Java, compatible with JDK 8 and above.

| | |
| --- | --- |
| Coordinates | `cn.chenxinjie:upload-file:1.0.0-rc.6` (parent POM / aggregator) |
| Minimum runtime | JDK 8 |
| Runtime dependency | Gson only (core module) |
| Modules | `upload-file-core` · `upload-file-servlet` · `upload-file-servlet-jakarta` · `upload-file-spring-boot-starter` · `upload-file-spring-boot-starter-jakarta` · `upload-file-store-jdbc` · `upload-file-store-redis` · `example/upload-file-demo` · `example/upload-file-boot4-demo` · `example/upload-file-servlet-demo` |

> 🚧 Status: **Pre-release** `1.0.0-rc.6` — API may change before the final `1.0.0`. See [Changelog](CHANGELOG.md).

> 🇨🇳 [简体中文](README.zh-CN.md)

## Features

- **Chunked upload** – split a large file into chunks and upload them sequentially; only failed chunks are re-transferred
- **Resumable upload** – the server records uploaded chunks; clients can pause and resume at any time
- **Chunk integrity** – optional per-chunk MD5 verification
- **Chunk merge** – merge chunks in order, validate the final file size, and clean up chunks automatically
- **Atomic merge** – merge to a temp file, optionally fsync, then atomically rename into place; a mid-write failure never leaves a corrupt file
- **Async merge** – run the merge in the background and poll its status (`mergeAsync` / `mergeStatus`)
- **Expired-task & orphan cleanup** – TTL-based cleanup of incomplete tasks and opt-in orphan-data GC
- **Resumable download** – HTTP `Range` based resumable download (`206 Partial Content`)
- **Metadata persistence** – upload progress can be persisted as JSON, or backed by JDBC / Redis via the `TaskStore` SPI
- **Multiple integrations** – plain Servlet, Spring Boot auto-configuration, or direct core API
- **Access control** – optional shared-token check on every endpoint (`401`); all endpoints accept the token in a configurable header or a `token` query param
- **Size & quota limits** – per-file total size cap and an optional global capacity quota (`400` / `507 Insufficient Storage`)
- **Cleanup observability** – structured stats log and a queryable `CleanupStats` snapshot per cleanup pass
- **Task-store migration** – `TaskStoreMigrator` copies in-flight tasks between stores (e.g. `FileTaskStore` → JDBC/Redis)
- **Metadata versioning** – `schemaVersion` field so the metadata format can evolve safely
- **Multi-instance coordination** – optional Redis lease lock so only one instance runs the cleanup scheduler
- **Explicit task read & cancel** – `getTask(identifier)` as the stable read for the integration "confirm" phase, and `cancelUpload(identifier)` / HTTP `POST /upload?action=cancel` that reclaim a task's chunks and merged artifact instead of waiting for the cleanup scheduler
- **Stable error semantics** – core failures carry an `UploadErrorCode` with a stable HTTP status (`400`/`401`/`404`/`409`/`507`) that servlet and Spring integrations map automatically

## Modules

| Module | Description | How to use |
| --- | --- | --- |
| `upload-file-core` | Core pure-Java components: models, checksum, storage SPI, upload/download/cleanup services | Any Java/Maven project |
| `upload-file-servlet` | Servlet 3.0+ (`javax.servlet`) integration: chunk-upload Servlet and Range-download Servlet | Servlet 3/4 container projects |
| `upload-file-servlet-jakarta` | Jakarta Servlet 5/6 (`jakarta.servlet`) twin of `upload-file-servlet` (same FQCNs — drop-in) | Tomcat 10/11, Boot 3/4 projects |
| `upload-file-spring-boot-starter` | Spring Boot 2.x (`javax.servlet`) auto-configuration, zero-config out of the box | Spring Boot 2.x projects |
| `upload-file-spring-boot-starter-jakarta` | Spring Boot 3/4 (`jakarta.servlet`) twin of the starter (same `upload-file.*` properties — drop-in) | Spring Boot 4.0.0+ projects (3.x expected) |
| `upload-file-store-jdbc` | Optional: JDBC-backed `TaskStore` (auto table creation, H2 test) | when `metadata-store=jdbc` |
| `upload-file-store-redis` | Optional: Redis-backed `TaskStore` (Jedis) | when `metadata-store=redis` |
| `example/upload-file-demo` | Demo app: Spring Boot 2 + frontend page showing the full resumable workflow | — |
| `example/upload-file-boot4-demo` | Demo app: Spring Boot 4 (`jakarta`) using `upload-file-spring-boot-starter-jakarta` | — |
| `example/upload-file-servlet-demo` | Demo app: plain Servlet (no Spring), wired via `web.xml` | — |

## Quick Start

### Option 1: Spring Boot project (recommended)

**Spring Boot 4.0.0+ (or 3.x — the jakarta stack):** use the jakarta starter (JDK 17+ at runtime):

```xml
<dependency>
    <groupId>cn.chenxinjie</groupId>
    <artifactId>upload-file-spring-boot-starter-jakarta</artifactId>
    <version>1.0.0-rc.6</version>
</dependency>
```

**Spring Boot 2.x (`javax.servlet`):** use the original starter:

```xml
<dependency>
    <groupId>cn.chenxinjie</groupId>
    <artifactId>upload-file-spring-boot-starter</artifactId>
    <version>1.0.0-rc.6</version>
</dependency>
```

The two starters share the same package names (`cn.chenxinjie.uploadfile.springboot.*`), the same
`upload-file.*` properties and the same endpoints, so switching Boot generations only changes the Maven
coordinate. **Never put a `javax` artifact and its `-jakarta` twin on one classpath** — pick one.

Configure `application.yml`:

```yaml
upload-file:
  storage-dir: ./data/upload            # root dir for chunks and merged files
  metadata-dir: ./data/upload/meta      # task metadata dir (leave empty to use in-memory)
  verify-checksum: true
```

Available endpoints after startup:

- `POST /upload` – upload one chunk
- `GET /upload?action=progress&identifier=xxx` – query upload progress (`GET /upload` requires a known `action` since rc.6)
- `POST /upload?action=merge&identifier=xxx` – merge chunks
- `POST /upload?action=mergeAsync&identifier=xxx` – submit an async merge (HTTP `202`), poll with `mergeStatus`
- `GET /upload?action=mergeStatus&identifier=xxx` – query the async merge status
- `POST /upload?action=cancel&identifier=xxx` – cancel a task and reclaim its data
- `GET /download?identifier=xxx` – download (supports the `Range` header for resumable download). Registered only
  when `upload-file.endpoint.download-enabled=true` (off by default since rc.6).

> The auto-configuration is discovered through Spring Boot's `AutoConfiguration.imports` on Boot 3/4 and
> through `spring.factories` on Boot 2.x. The servlet layer targets `javax.servlet` (Boot 2 / Servlet 3.1)
> or `jakarta.servlet` (Boot 3/4 / Tomcat 10+) depending on the artifact you pick.

### Option 2: Plain Servlet container

Depend on `upload-file-servlet` (Servlet 3/4, `javax`) or `upload-file-servlet-jakarta` (Servlet 5/6,
`jakarta`, e.g. Tomcat 10/11); the two servlets (`/upload`, `/download`) are registered via annotation
scanning. Requires Servlet 3.0+ (downloading a range over 2 GB requires Servlet 3.1+). Storage
directories can be configured with init-params:

```xml
<servlet>
    <servlet-name>uploadFileServlet</servlet-name>
    <servlet-class>cn.chenxinjie.uploadfile.servlet.UploadServlet</servlet-class>
    <init-param><param-name>storage-dir</param-name><param-value>/data/upload</param-value></init-param>
    <init-param><param-name>metadata-dir</param-name><param-value>/data/upload/meta</param-value></init-param>
</servlet>
<servlet-mapping>
    <servlet-name>uploadFileServlet</servlet-name>
    <url-pattern>/upload</url-pattern>
</servlet-mapping>
```

### Option 3: Core API only

Depend on `upload-file-core` and code directly:

```java
TaskStore store = new FileTaskStore("/data/upload/meta");
ChunkStorage chunks = new LocalFileChunkStorage("/data/upload/chunks");
ResumableUploadService service = new ResumableUploadService(store, chunks, new File("/data/upload/files"));

// upload a chunk
service.uploadChunk(request, chunkInputStream);
// query progress / merge
UploadProgress progress = service.getProgress(identifier);
UploadResult result = service.merge(identifier);
```

**Confirm phase (rc.4):** locate the merged artifact through the merge result or the task record, never
by guessing the directory layout, and reclaim the task afterwards:

```java
UploadTask task = service.getTask(identifier).get();        // stable per-identifier read
Path artifact = Paths.get(task.getFinalPath());              // authoritative merged location
Files.move(artifact, businessDir.resolve(fileName));         // move into business storage
service.cancelUpload(identifier);                            // remove task + leftover data
```

When no task exists `getTask` returns empty; `cancelUpload` returns `false` and throws `409`
while an async merge is pending/running.

## Configuration Reference (Spring Boot)

| Property | Default | Description |
| --- | --- | --- |
| `upload-file.storage-dir` | `./upload-file-data` | Root dir for chunks and merged files |
| `upload-file.metadata-dir` | *(empty)* | Task metadata dir; empty = in-memory (lost on restart) |
| `upload-file.metadata-store` | `auto` | `auto` (file when `metadata-dir` set, otherwise memory) / `memory` / `file` / `jdbc` / `redis` |
| `upload-file.verify-checksum` | `true` | Verify per-chunk MD5 |
| `upload-file.upload-url` | `/upload` | Upload servlet mapping |
| `upload-file.download-url` | `/download` | Download servlet mapping |
| `upload-file.max-chunk-size` | `-1` | Max bytes per chunk: enforced at the multipart layer and again by the service; `-1` = unlimited |
| `upload-file.max-request-size` | `-1` | Max request size in bytes (multipart); `-1` = unlimited |
| `upload-file.merge.fsync` | `true` | fsync the merge temp file before renaming |
| `upload-file.merge.atomic` | `true` | Merge via temp file + atomic move |
| `upload-file.cleanup.enabled` | `false` | Start the expired-task / orphan cleanup scheduler |
| `upload-file.cleanup.run-on-startup` | `false` | Run one cleanup pass at startup |
| `upload-file.cleanup.interval` | `1h` | Cleanup period |
| `upload-file.cleanup.task-ttl` | `24h` | Expiry of incomplete tasks; `0` = never clean |
| `upload-file.cleanup.orphan-enabled` | `false` | Enable orphan-data cleanup (requires a persistent store) |
| `upload-file.async-merge.enabled` | `false` | Enable async merge |
| `upload-file.async-merge.thread-pool-size` | `2` | Async merge thread count |
| `upload-file.jdbc.table-name` | `upload_task` | JDBC table name |
| `upload-file.jdbc.init-sql` | `CREATE TABLE IF NOT EXISTS %s (...)` | SQL to auto-create the JDBC table; the table name is substituted for the first `%s` |
| `upload-file.redis.host` | `localhost` | Redis host |
| `upload-file.redis.port` | `6379` | Redis port |
| `upload-file.redis.password` | *(empty)* | Redis password; empty = no auth |
| `upload-file.redis.key-prefix` | `upload:task:` | Redis key prefix |
| `upload-file.redis.ttl-seconds` | `0` | Redis record TTL; `0` = none |
| `upload-file.security.enabled` | `false` | Enable access-control checks (requires a token) |
| `upload-file.security.token` | *(empty)* | Shared access token; empty = no checks |
| `upload-file.security.header-name` | `X-Access-Token` | Token header name (a `token` query param is also accepted) |
| `upload-file.max-file-size` | `-1` | Per-file total size limit in bytes; `-1` = unlimited |
| `upload-file.quota.max-bytes` | `0` | Global capacity quota in bytes; `0` = off |
| `upload-file.cleanup.use-redis-lock` | `false` | Use a Redis lease lock so only one instance runs cleanup |
| `upload-file.observability.log-stats` | `true` | Log a structured cleanup-stats line after each pass |
| `upload-file.migration.enabled` | `false` | Expose the `TaskStoreMigrator` bean (migration never runs automatically) |
| `upload-file.endpoint.enabled` | `true` | Master endpoint switch (rc.6); `false` = beans-only (services wired, no servlet registered) |
| `upload-file.endpoint.upload-enabled` | `true` | Register the upload servlet (rc.6) |
| `upload-file.endpoint.download-enabled` | `false` | Register the download servlet (rc.6) — off by default (minimal exposure) |
| `upload-file.http.error-body` | `legacy` | Failure body (rc.6): `legacy` (per-endpoint models) or `standard` (`UploadHttpError` + symbolic code) |
| `upload-file.http.cancel-not-found-status` | `404` | Status for canceling a missing task (rc.6); `200` = idempotent reclaim |
| `upload-file.multipart.strategy` | `component` | Multipart limits (rc.6): `component` / `spring` (follow `spring.servlet.multipart.*`) / `unlimited` |
| `upload-file.observability.access-log` | `false` | Log one structured access-decision line per entry-point check (rc.6) |

The dotted names above map to nested groups, so the same settings can be written in a grouped
YAML form:

```yaml
upload-file:
  storage-dir: ./data/upload
  verify-checksum: true
  merge:
    fsync: true
    atomic: true
  cleanup:
    enabled: true
    interval: 1h
    task-ttl: 24h
    use-redis-lock: true
  async-merge:
    enabled: true
    thread-pool-size: 2
  security:
    enabled: true
    token: change-me
    header-name: X-Access-Token
  quota:
    max-bytes: 10737418240
  observability:
    log-stats: true
  jdbc:
    table-name: upload_task
  redis:
    host: localhost
    port: 6379
    key-prefix: upload:task:
```

> Pure Servlet deployments configure the same options as init-params (e.g. `chunk.max-size`,
> `cleanup.enabled`, `async-merge.enabled`, `security.token`, `max-file-size`, `quota.max-bytes`).

## Access Control

When `upload-file.security.enabled=true` and a token is configured, every endpoint requires the
token in the header named by `security.header-name` (default `X-Access-Token`) or in a `token`
query parameter. Requests without a valid token are rejected with `401`. Enabling security
without a token fails fast at startup so a misconfiguration never silently opens the endpoints.
When security is off (the default), behavior is unchanged.

> **Existing-login integrations:** to reuse your own session (Bearer/SSO) instead of a shared token,
> implement the `AccessControl` SPI once and delegate `check(...)` to your principal — the core invokes
> it at every endpoint. A Spring Security filter in front of `/upload` also works and is what most
> single-tenant integrations do; the component only enforces its own SPI when it is installed.

## Integration guidance (since 1.0.0-rc.4)

### Locating the merged artifact ("confirm" phase)

The merged file's location is a contract: read it from the merge result (`UploadResult.finalPath`,
`MergeStatus.finalPath`) or from the task record via `getTask(identifier).getFinalPath()` — do not
re-derive `{storage-dir}/files/{identifier}/{fileName}` yourself. Typical flow:

```java
// after the frontend reports merge SUCCEEDED / the sync merge returned
UploadTask task = service.getTask(identifier).get();          // stable read (rc.4)
Path artifact = Paths.get(task.getFinalPath());               // authoritative path
Files.move(artifact, businessDir.resolve(task.getFileName())); // same disk => atomic move
service.cancelUpload(identifier);                              // reclaim record + leftovers (rc.4)
```

`cancelUpload` removes the task record, its chunks and the merged artifact dir, returns `false` when
nothing existed, and throws `409` while an async merge is pending/running (retry once it settles).

### When is on-disk data reclaimed?

- **Expired incomplete tasks** — `StorageCleanupService`'s TTL pass removes tasks idle longer than
  `cleanup.task-ttl` together with their chunks.
- **Orphans** — a chunk/merged dir whose task record is gone (e.g. after Redis metadata TTL expiry) is
  removed by the opt-in orphan pass (`cleanup.orphan-enabled: true`). Orphan GC is never run against the
  in-memory store.
- **Merged-but-unclaimed artifacts of a live task are intentionally kept** — they are valid download
  candidates, so they are only reclaimed by an explicit `cancelUpload` or after the task record expires.
- **Recommendation:** wire the cleanup scheduler **and** call `cancelUpload` at confirm, so a multi-hundred-MB
  merged artifact never has to wait for a TTL. Starter: `cleanup.enabled: true`, `cleanup.orphan-enabled: true`.
  Core (manual): construct a `StorageCleanupService` sharing the upload service's `IdentifierLock` and call
  `cleanup()` / `start(intervalMillis)`.

### Manual (core) wiring consumes no `upload-file.*` properties

Property binding, cleanup scheduling and the async pool live in the **Spring Boot starter**. When you
hand-assemble `upload-file-core` (no starter), the following are your responsibility to configure
programmatically — setting them in `application.yml` has no effect:
`cleanup.enabled/interval/task-ttl/orphan-enabled/use-redis-lock`, `async-merge.enabled/thread-pool-size`,
`max-request-size`, `security.enabled/token`, plus the multipart limits.
Async merge is on only while `setAsyncExecutor(executor)` has been called (pass `null` or omit it to stay
synchronous); cleanup only runs when you start its scheduler. The servlet module reads the same options as
init-params instead.

### Stable error semantics in your own HTTP layer

Core exceptions describing a client-recoverable failure implement `UploadErrorCode`:

| `UploadErrorCode` | HTTP | Thrown by |
| --- | --- | --- |
| `UploadValidationException` (a `IllegalArgumentException`) | `400` | invalid chunk params, metadata disagreement, size limits, missing chunks on merge |
| `ChecksumMismatchException` | `400` | per-chunk MD5 mismatch |
| `AccessDeniedException` | `401` | access-control rejection |
| `UploadTaskNotFoundException` (a `NoSuchElementException`) | `404` | merge/submit on an unknown task |
| `UploadMergeConflictException` (an `IllegalStateException`) | `409` | chunk upload to merged/running task, cancel during async merge |
| `QuotaExceededException` | `507` | global `quota.max-bytes` exceeded |
| anything else | `500` | server-side failure |

The typed exceptions subclass their generic Java counterparts, so existing
`catch (IllegalArgumentException / NoSuchElementException / IllegalStateException)` code keeps working.
In a Spring `@ExceptionHandler`:

```java
@ExceptionHandler
ResponseEntity<?> onUploadError(Exception e) {
    int status = e instanceof UploadErrorCode ? ((UploadErrorCode) e).getHttpStatusCode() : 500;
    return ResponseEntity.status(status).body(Map.of("code", status, "message", e.getMessage()));
}
```

The official servlet applies this mapping and returns a JSON body automatically.

### Range parsing without the download servlet

`DownloadRange.parse(String)` in core parses single/multi-part `Range` headers and detects
unsatisfiable ranges, so integrations that serve their own storage (e.g. files that were moved out of
the component during the confirm phase) can reuse the parser instead of rewriting the range logic.
Downloading a *component-merged* artifact is still best done through the official `/download` endpoint.

## HTTP API Overview

| Method & Path | Description |
| --- | --- |
| `POST /upload` (multipart, file field `file`) | Upload one chunk. Params: `identifier`, `fileName`, `fileSize`, `chunkSize`, `chunkTotal`, `chunkIndex`, `chunkMd5`. Returns progress JSON |
| `GET /upload?action=progress&identifier=xxx` | Query upload progress |
| `POST /upload?action=merge&identifier=xxx` | Merge all chunks. Returns result JSON |
| `POST /upload?action=mergeAsync&identifier=xxx` | Submit an async merge (`202`); new chunks are rejected while pending/running/succeeded |
| `GET /upload?action=mergeStatus&identifier=xxx` | Query the async merge status (`NONE/PENDING/RUNNING/SUCCEEDED/FAILED`) |
| `POST /upload?action=cancel&identifier=xxx` | Cancel a task and reclaim its chunks/merged artifact |
| `GET /download?identifier=xxx` | Full download (`200`) |
| `GET /download?identifier=xxx` + `Range` header | Range download (`206` / `416`) |

Error responses carry a JSON body and a stable status: `400` invalid request / exceeds size limits /
MD5 mismatch, `401` access denied, `404` task not found, `409` merge-state conflict (see the table above),
`507` quota exceeded, `416` unsatisfiable range.

## Build & Test

```bash
mvn install
```

- Requires Maven 3.6.3+ and JDK 8+
- Compiles with `--release 8`, producing JDK 8 bytecode — **usable directly on JDK 8**
- Because `--release` is used, building from source requires JDK 9+ (to build on a JDK 8 toolchain, remove `maven.compiler.release` from the parent POM)
- Since `1.0.0-rc.5` the reactor also contains the jakarta modules (`upload-file-servlet-jakarta`,
  `upload-file-spring-boot-starter-jakarta`, `example/upload-file-boot4-demo`), whose Spring Boot 4 /
  Servlet 6 dependencies need a **JDK 17+** toolchain. A full root `mvn verify` therefore runs on JDK 17+;
  to build only the JDK-8 `javax` line on a JDK 8 toolchain use a subset build, e.g.
  `mvn install -pl upload-file-core,upload-file-servlet,upload-file-spring-boot-starter -am`.

## Run the Demo

**Spring Boot 4 demo** (`example/upload-file-boot4-demo`, uses `upload-file-spring-boot-starter-jakarta`):

```bash
mvn -pl example/upload-file-boot4-demo spring-boot:run
```

Open <http://localhost:8080/> and exercise chunked upload, pause/resume, async merge and resumable download
on a real Boot 4 (`jakarta`) runtime.

**Spring Boot 2 demo** (`example/upload-file-demo`):

```bash
mvn -pl example/upload-file-demo spring-boot:run
# or
java -jar example/upload-file-demo/target/upload-file-demo-1.0.0-rc.6.jar
```

Open <http://localhost:8080/>, pick a file, and try chunked upload, pause/resume, merge, and resumable download.

**Plain Servlet demo** (`example/upload-file-servlet-demo`, no Spring, wired via `web.xml`):

```bash
mvn -pl example/upload-file-servlet-demo jetty:run
```

Open <http://localhost:8080/> and use the same frontend page; it exercises `UploadServlet` / `DownloadServlet`
directly with the `storage-dir` / `metadata-dir` init-params declared in `web.xml`.

## Security

- `identifier` and `fileName` are validated to prevent path traversal (validated in every store implementation)
- Optional per-chunk MD5 verification
- Chunks and metadata are written atomically (temp file + rename); merge is atomic as well
- A `max-chunk-size` / `chunk.max-size` limit rejects oversized chunks (disk-exhaustion protection)
- A `max-file-size` per-file limit and an optional `quota.max-bytes` global quota reject oversized files before they are persisted
- Optional shared-token access control on every endpoint (`security.*`), compared in constant time; off by default
- Chunk metadata is checked for cross-chunk consistency; later chunks that disagree with the first are rejected
- Cleanup and upload/merge share a per-identifier lock, so background GC never races live data
- An optional Redis cleanup lease lock keeps multiple instances from running duplicate cleanup

## Docs

- [Design](docs/DESIGN.md)
- [Future Optimization Directions](docs/ROADMAP.md)
- [HTTP API reference](docs/API.md)
- [Changelog](CHANGELOG.md)
- [V1.0.0-rc.6 Task Plan (commercial HTTP-layer adoption)](docs/PLAN-V1.0.0-rc.6.md)
- [V1.0.0-rc.5 Task Plan (Spring Boot 4 / jakarta starter)](docs/PLAN-V1.0.0-rc.5.md)
- [V1.0.0-rc.4 Task Plan (feedback-driven integration)](docs/PLAN-V1.0.0-rc.4.md)
- [V1.0.0-rc.3 Task Plan (production hardening)](docs/PLAN-V1.0.0-rc.3.md)

## License

[MIT](LICENSE)
