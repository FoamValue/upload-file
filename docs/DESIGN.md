# Design

> 🇨🇳 [简体中文](DESIGN.zh-CN.md)

## Module Dependencies

```
upload-file (parent POM / aggregator)
├── upload-file-core                   pure Java, no framework dependencies
└── upload-file-servlet                Servlet 3.0+ integration layer
└── upload-file-spring-boot-starter    Spring Boot auto-configuration
└── example/upload-file-demo           demo application
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
| `TaskStore` (SPI) | Read/write upload-task metadata. Built-ins: `MemoryTaskStore`, `FileTaskStore` |
| `ChunkStorage` (SPI) | Physical chunk storage. Built-in: `LocalFileChunkStorage` |
| `ResumableUploadService` | Chunk persistence, progress tracking, MD5 verification, merge & cleanup |
| `ResumableDownloadService` | Locate the merged file and read a `Range` slice |
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

`identifier` is validated by `Strings.requireSafeIdentifier` (no `/`, `\`, `.`, `..`);
`fileName` is validated by `Strings.requireSafeFileName` (re-validated before the merged
file is written), preventing path traversal. Both storage implementations also validate
the identifier internally, defending against callers that use the SPI directly.

## Chunked Upload Flow

```
Client                                       Server
   │  compute whole-file MD5 → identifier          │
   │  upload each chunk (multipart)                │
   │ ───────────────────────────────────────▶ ResumableUploadService
   │                                            │ 1. load/create UploadTask
   │                                            │ 2. already uploaded? → skip (idempotent)
   │                                            │ 3. ChunkStorage.saveChunk (atomic rename)
   │                                            │ 4. optional: verify chunk MD5
   │                                            │ 5. markUploaded + TaskStore.save
   │ ◀─────────────────────────────────────── return UploadProgress (JSON)
   │  call merge after all chunks are uploaded   │
   │ ───────────────────────────────────────▶ merge in order → files/<id>/<fileName>
   │                                            │ verify final size → delete chunks → mark merged
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
  tracking consistent under concurrent uploads.
- Chunks and metadata are written via "temp file + atomic rename", avoiding half-written
  files on interruption.
- Re-uploading the same chunk is idempotent (skipped once recorded).
