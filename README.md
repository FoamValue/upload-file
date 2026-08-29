# upload-file

Maven toolkit for **large-file chunked upload / resumable (breakpoint) upload / HTTP `Range` resumable download**. Pure Java, compatible with JDK 8 and above.

| | |
| --- | --- |
| Coordinates | `cn.chenxinjie:upload-file:1.0.0-rc.3` (parent POM / aggregator) |
| Minimum runtime | JDK 8 |
| Runtime dependency | Gson only (core module) |
| Modules | `upload-file-core` · `upload-file-servlet` · `upload-file-spring-boot-starter` · `upload-file-store-jdbc` · `upload-file-store-redis` · `example/upload-file-demo` · `example/upload-file-servlet-demo` |

> 🚧 Status: **Pre-release** `1.0.0-rc.3` — API may change before the final `1.0.0`. See [Changelog](CHANGELOG.md).

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

## Modules

| Module | Description | How to use |
| --- | --- | --- |
| `upload-file-core` | Core pure-Java components: models, checksum, storage SPI, upload/download/cleanup services | Any Java/Maven project |
| `upload-file-servlet` | Servlet 3.0+ integration: chunk-upload Servlet and Range-download Servlet | Servlet container projects |
| `upload-file-spring-boot-starter` | Spring Boot 2.x auto-configuration, zero-config out of the box | Spring Boot projects |
| `upload-file-store-jdbc` | Optional: JDBC-backed `TaskStore` (auto table creation, H2 test) | when `metadata-store=jdbc` |
| `upload-file-store-redis` | Optional: Redis-backed `TaskStore` (Jedis) | when `metadata-store=redis` |
| `example/upload-file-demo` | Demo app: Spring Boot + frontend page showing the full resumable workflow | — |
| `example/upload-file-servlet-demo` | Demo app: plain Servlet (no Spring), wired via `web.xml` | — |

## Quick Start

### Option 1: Spring Boot project (recommended)

```xml
<dependency>
    <groupId>cn.chenxinjie</groupId>
    <artifactId>upload-file-spring-boot-starter</artifactId>
    <version>1.0.0-rc.3</version>
</dependency>
```

Configure `application.yml`:

```yaml
upload-file:
  storage-dir: ./data/upload            # root dir for chunks and merged files
  metadata-dir: ./data/upload/meta      # task metadata dir (leave empty to use in-memory)
  verify-checksum: true
```

Available endpoints after startup:

- `POST /upload` – upload one chunk
- `GET /upload?action=progress&identifier=xxx` – query upload progress
- `POST /upload?action=merge&identifier=xxx` – merge chunks
- `POST /upload?action=mergeAsync&identifier=xxx` – submit an async merge (HTTP `202`), poll with `mergeStatus`
- `GET /upload?action=mergeStatus&identifier=xxx` – query the async merge status
- `GET /download?identifier=xxx` – download (supports the `Range` header for resumable download)

### Option 2: Plain Servlet container

Depend on `upload-file-servlet`; the two servlets (`/upload`, `/download`) are registered via annotation scanning. Requires Servlet 3.0+ (downloading a range over 2 GB requires Servlet 3.1+). Storage directories can be configured with init-params:

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

## HTTP API Overview

| Method & Path | Description |
| --- | --- |
| `POST /upload` (multipart, file field `file`) | Upload one chunk. Params: `identifier`, `fileName`, `fileSize`, `chunkSize`, `chunkTotal`, `chunkIndex`, `chunkMd5`. Returns progress JSON |
| `GET /upload?action=progress&identifier=xxx` | Query upload progress |
| `POST /upload?action=merge&identifier=xxx` | Merge all chunks. Returns result JSON |
| `POST /upload?action=mergeAsync&identifier=xxx` | Submit an async merge (`202`); new chunks are rejected while pending/running/succeeded |
| `GET /upload?action=mergeStatus&identifier=xxx` | Query the async merge status (`NONE/PENDING/RUNNING/SUCCEEDED/FAILED`) |
| `GET /download?identifier=xxx` | Full download (`200`) |
| `GET /download?identifier=xxx` + `Range` header | Range download (`206` / `416`) |

Common errors: `400` invalid request / exceeds `max-file-size`, `401` access denied (when access
control is enabled), `404` file not found, `507 Insufficient Storage` exceeds `quota.max-bytes`,
`416` unsatisfiable range.

## Build & Test

```bash
mvn install
```

- Requires Maven 3.6.3+ and JDK 8+
- Compiles with `--release 8`, producing JDK 8 bytecode — **usable directly on JDK 8**
- Because `--release` is used, building from source requires JDK 9+ (to build on a JDK 8 toolchain, remove `maven.compiler.release` from the parent POM)

## Run the Demo

**Spring Boot demo** (`example/upload-file-demo`):

```bash
mvn -pl example/upload-file-demo spring-boot:run
# or
java -jar example/upload-file-demo/target/upload-file-demo-1.0.0-rc.3.jar
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
- [V1.0.0-rc.3 Task Plan (production hardening)](docs/PLAN-V1.0.0-rc.3.md)

## License

[MIT](LICENSE)
