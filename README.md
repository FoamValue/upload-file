# upload-file

Maven toolkit for **large-file chunked upload / resumable (breakpoint) upload / HTTP `Range` resumable download**. Pure Java, compatible with JDK 8 and above.

| | |
| --- | --- |
| Coordinates | `cn.chenxinjie:upload-file:1.0.0` (parent POM / aggregator) |
| Minimum runtime | JDK 8 |
| Runtime dependency | Gson only (core module) |
| Modules | `upload-file-core` · `upload-file-servlet` · `upload-file-spring-boot-starter` · `upload-file-demo` |

> 🇨🇳 [简体中文](README.zh-CN.md)

## Features

- **Chunked upload** – split a large file into chunks and upload them sequentially; only failed chunks are re-transferred
- **Resumable upload** – the server records uploaded chunks; clients can pause and resume at any time
- **Chunk integrity** – optional per-chunk MD5 verification
- **Chunk merge** – merge chunks in order, validate the final file size, and clean up chunks automatically
- **Resumable download** – HTTP `Range` based resumable download (`206 Partial Content`)
- **Metadata persistence** – upload progress can be persisted as JSON and survives server restarts
- **Multiple integrations** – plain Servlet, Spring Boot auto-configuration, or direct core API

## Modules

| Module | Description | How to use |
| --- | --- | --- |
| `upload-file-core` | Core pure-Java components: models, checksum, storage SPI, upload/download services | Any Java/Maven project |
| `upload-file-servlet` | Servlet 3.0+ integration: chunk-upload Servlet and Range-download Servlet | Servlet container projects |
| `upload-file-spring-boot-starter` | Spring Boot 2.x auto-configuration, zero-config out of the box | Spring Boot projects |
| `example/upload-file-demo` | Demo app: Spring Boot + frontend page showing the full resumable workflow | — |

## Quick Start

### Option 1: Spring Boot project (recommended)

```xml
<dependency>
    <groupId>cn.chenxinjie</groupId>
    <artifactId>upload-file-spring-boot-starter</artifactId>
    <version>1.0.0</version>
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
| `upload-file.verify-checksum` | `true` | Verify per-chunk MD5 |
| `upload-file.upload-url` | `/upload` | Upload servlet mapping |
| `upload-file.download-url` | `/download` | Download servlet mapping |
| `upload-file.max-chunk-size` | `-1` | Max chunk size in bytes (multipart); `-1` = unlimited |
| `upload-file.max-request-size` | `-1` | Max request size in bytes (multipart); `-1` = unlimited |

## HTTP API Overview

| Method & Path | Description |
| --- | --- |
| `POST /upload` (multipart, file field `file`) | Upload one chunk. Params: `identifier`, `fileName`, `fileSize`, `chunkSize`, `chunkTotal`, `chunkIndex`, `chunkMd5`. Returns progress JSON |
| `GET /upload?action=progress&identifier=xxx` | Query upload progress |
| `POST /upload?action=merge&identifier=xxx` | Merge all chunks. Returns result JSON |
| `GET /download?identifier=xxx` | Full download (`200`) |
| `GET /download?identifier=xxx` + `Range` header | Range download (`206` / `416`) |

## Build & Test

```bash
mvn install
```

- Requires Maven 3.6.3+ and JDK 8+
- Compiles with `--release 8`, producing JDK 8 bytecode — **usable directly on JDK 8**
- Because `--release` is used, building from source requires JDK 9+ (to build on a JDK 8 toolchain, remove `maven.compiler.release` from the parent POM)

## Run the Demo

```bash
mvn -pl example/upload-file-demo spring-boot:run
# or
java -jar example/upload-file-demo/target/upload-file-demo-1.0.0.jar
```

Open <http://localhost:8080/>, pick a file, and try chunked upload, pause/resume, merge, and resumable download.

## Security

- `identifier` and `fileName` are validated to prevent path traversal
- Optional per-chunk MD5 verification
- Chunks and metadata are written atomically (temp file + rename)

## Docs

- [Design](docs/DESIGN.md)
- [HTTP API reference](docs/API.md)
