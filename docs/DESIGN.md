# Design

> 🇨🇳 [简体中文](DESIGN.zh-CN.md)

## Module Dependencies

```
upload-file (parent POM / aggregator)
├── upload-file-core                   pure Java, no framework dependencies
├── upload-file-servlet                Servlet 3.0+ integration layer
├── upload-file-spring-boot-starter    Spring Boot auto-configuration
├── upload-file-store-jdbc             optional: JDBC TaskStore (H2 test)
├── upload-file-store-redis            optional: Redis TaskStore (Jedis)
├── example/upload-file-demo            demo: Spring Boot + frontend page
└── example/upload-file-servlet-demo    demo: plain Servlet wired via web.xml
```

## Core Concepts

- **identifier** – the unique ID of a file, by convention the MD5 of the whole file.
  All chunks, task records, and merged files on the server are keyed by it; the client
  uses it to implement resumable upload (idempotent).
- **chunk** – a fixed-size slice of the file; indices start at 0.
- **UploadTask** – metadata that records the identifier, file name, size, chunk count,
  and the set of uploaded chunk indices; persisted in the `TaskStore`.

## Component Responsibilities

| Component | Responsibility |
| --- | --- |
| `TaskStore` (SPI) | Read/write upload-task metadata. Built-ins: `MemoryTaskStore`, `FileTaskStore`, `JdbcTaskStore`, `RedisTaskStore` |
| `ChunkStorage` (SPI) | Physical chunk storage. Built-in: `LocalFileChunkStorage` |
| `ResumableUploadService` | Chunk persistence, progress tracking, MD5 verification, merge & cleanup |
| `ResumableDownloadService` | Locate the merged file and read a `Range` slice |
| `StorageCleanupService` | Background TTL-based task expiry and opt-in orphan-data GC |
| `IdentifierLock` | Fixed-size striped lock keyed by identifier, shared by upload and cleanup |
| `UploadServlet` / `DownloadServlet` | HTTP integration; parse multipart / Range |
| `UploadFileAutoConfiguration` | Spring Boot auto-wiring and Servlet registration |

## Storage Layout

`FileTaskStore` / `LocalFileChunkStorage` use the following layout:

```
<storage-dir>/
├── chunks/<identifier>/<index>.part   # chunks (written via temp file + atomic rename)
├── files/<identifier>/<fileName>      # merged complete file
└── (metadata-dir)/
    └── <identifier>.json              # task metadata (temp file + atomic rename)
```

`identifier` is validated by `StringUtil.requireSafeIdentifier` (no `/`, `\`, `.`, `..`);
`fileName` is validated by `StringUtil.requireSafeFileName` (re-validated before the merged
file is written), preventing path traversal. Every store implementation validates the
identifier internally, defending against callers that use the SPI directly.

## Chunked Upload Flow

```
Client                                       Server
   │  compute whole-file MD5 → identifier          │
   │  upload each chunk (multipart)                │
   │ ───────────────────────────────────────▶ ResumableUploadService
   │    │ 1. load/create UploadTask; metadata of later chunks must match the first chunk
   │    │ 2. already uploaded? → skip (idempotent)
   │    │ 3. optional: reject chunks over the size limit
   │    │ 4. ChunkStorage.saveChunk (atomic rename)
   │    │ 5. optional: verify chunk MD5
   │    │ 6. markUploaded + TaskStore.save
   │ ◀─────────────────────────────────────── return UploadProgress (JSON)
   │  call merge after all chunks are uploaded   │
   │ ───────────────────────────────────────▶ merge in order → files/<id>/<fileName>
   │                                            │ verify size → persist merged state → delete chunks
```

## Resumable Download Flow

```
Client                                    Server
   │  GET /download?identifier=xxx          │
   │  Range: bytes=0-5999999                │
   │ ───────────────────────────────────▶   │ parse Range → 206 + Content-Range
   │  (network interrupted)                 │
   │  GET /download  Range: bytes=6000000-  │ resume from offset → 206
   │ ───────────────────────────────────▶   │
```

An unsatisfiable Range returns `416` with `Content-Range: bytes */<size>`.

## Extension Points

- **Swap task storage**: implement `TaskStore` to use Redis, a database, or cloud storage.
- **Swap chunk storage**: implement `ChunkStorage` to use OSS, HDFS, or S3.
- **Override default components**: under Spring Boot every core bean is
  `@ConditionalOnMissingBean`; define a bean with the same name to override it.

## Concurrency & Consistency

- Chunk operations for the same identifier are serialized by a striped lock keyed on the
  identifier (fixed-size lock buckets, bounded memory), keeping task creation and progress
  tracking consistent under concurrent uploads. The upload service and the cleanup service
  share one `IdentifierLock` instance, so cleanup never races an in-flight upload/merge of
  the same identifier (deletions are double-checked under the lock).
- Chunks and metadata are written via "temp file + atomic rename", avoiding half-written
  files on interruption; merge follows the same pattern.
- On merge the merged state is persisted **before** the chunks are deleted, so a failed
  metadata write leaves the task recoverable; chunk deletion afterwards is best-effort.
- The metadata captured from the first chunk is authoritative: later chunks whose declared
  `chunkTotal` / `chunkSize` / `fileSize` / `fileName` disagrees are rejected.
- Re-uploading the same chunk is idempotent (skipped once recorded).
- Orphan-data GC is skipped when the task store is in-memory, since all tasks are lost on
  restart and every on-disk dir would otherwise look like an orphan.

## Future Optimization Directions

See [Future Optimization Directions](ROADMAP.md) (roadmap with core directions and priority grouping).
